package com.avies.backend.exam;

import com.avies.backend.dto.request.*;
import com.avies.backend.entity.*;
import com.avies.backend.entity.enums.*;
import com.avies.backend.exception.*;
import com.avies.backend.repository.*;
import com.avies.backend.service.impl.StudentExamServiceImpl;
import com.avies.backend.service.support.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Kiểm tra service validate toàn batch trước lưu và bảo vệ bằng chứng, không cần database. */
@ExtendWith(MockitoExtension.class)
class StudentExamServiceTest {
    /** User repository giả lập chỉ cho kiểm tra thứ tự và số lần ghi. */
    @Mock private UserRepository users;
    /** Nguồn child giả lập. */
    @Mock private StudentExamRepository exams;
    /** Kiểm tra bằng chứng câu hỏi giả lập. */
    @Mock private StudentExamQuestionRepository questions;
    /** Policy tách khỏi service để giữ test tập trung vào batch. */
    @Mock private ExamAccessPolicy access;
    /** Persistence context giả lập, không mở connection. */
    @Mock private EntityManager entityManager;
    /** Service thật dùng planner thật. */
    private StudentExamServiceImpl service;

    /** Inject EM như Spring làm mà không dựng application context. */
    @BeforeEach
    void prepare() {
        service = new StudentExamServiceImpl(users, exams, questions, access, new ExamSlotPlanner());
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    /** Nếu một user không tồn tại thì không có child nào được lưu. */
    @Test
    void missingStudentPreventsAllWrites() {
        when(access.ownedForWrite(7L)).thenReturn(session());
        when(users.lockStudentsForAssignment(List.of(1L, 2L))).thenReturn(List.of(1L));
        assertCode(() -> service.assign(7L, request(1L, 2L)), ErrorCode.USER_NOT_EXISTED);
        verify(exams, never()).saveAllAndFlush(any());
    }

    /** Chỉ một role sai cũng khiến batch thất bại trước bước tính/lưu slot. */
    @Test
    void nonStudentPreventsPartialAssignment() {
        when(access.ownedForWrite(7L)).thenReturn(session());
        when(users.lockStudentsForAssignment(List.of(1L, 2L))).thenReturn(List.of(1L, 2L));
        when(users.findAllById(List.of(1L, 2L))).thenReturn(List.of(user(1, "STUDENT"), user(2, "LECTURER")));
        assertCode(() -> service.assign(7L, request(1L, 2L)), ErrorCode.ASSIGNMENT_NOT_STUDENT);
        verify(exams, never()).saveAllAndFlush(any());
    }

    /** ID trùng trong một batch chỉ giữ lần đầu, thứ tự quyết định slot. */
    @Test
    void duplicateIdsAreCoalescedBeforeValidationAndScheduling() {
        when(access.ownedForWrite(7L)).thenReturn(session());
        when(users.lockStudentsForAssignment(List.of(2L, 1L))).thenReturn(List.of(1L, 2L));
        when(users.findAllById(List.of(2L, 1L))).thenReturn(List.of(user(1, "STUDENT"), user(2, "STUDENT")));
        var response = service.assign(7L, request(2L, 1L, 2L));
        assertThat(response.getCreated()).extracting(r -> r.getStudentId()).containsExactly(2L, 1L);
        assertThat(response.getCreated()).extracting(r -> r.getSlotOrder()).containsExactly(1, 2);
        verify(exams).saveAllAndFlush(argThat(rows -> {
            List<StudentExam> list = (List<StudentExam>) rows;
            return list.size() == 2 && list.getFirst().getStatus() == StudentExamStatus.ASSIGNED;
        }));
    }

    /** Kiểm tra sức chứa toàn batch trước insert, không lưu riêng phần vừa đủ. */
    @Test
    void capacityFailurePreventsAllWrites() {
        var session = session();
        session.setScheduledEndAt(session.getScheduledStartAt().plusMinutes(20));
        when(access.ownedForWrite(7L)).thenReturn(session);
        when(users.lockStudentsForAssignment(List.of(1L, 2L))).thenReturn(List.of(1L, 2L));
        when(users.findAllById(List.of(1L, 2L))).thenReturn(List.of(user(1, "STUDENT"), user(2, "STUDENT")));
        assertCode(() -> service.assign(7L, request(1L, 2L)), ErrorCode.EXAM_SLOT_CAPACITY_EXCEEDED);
        verify(exams, never()).saveAllAndFlush(any());
    }

    /** Bất kỳ câu đã gán đều chặn thu hồi để giữ cả transcript bên dưới. */
    @Test
    void assignedQuestionsPreventRemovalAndCascade() {
        when(exams.findSessionIdForRemoval(8L)).thenReturn(Optional.of(7L));
        when(access.ownedForWrite(7L)).thenReturn(session());
        when(exams.lockForRemoval(8L)).thenReturn(Optional.of(8L));
        when(exams.findById(8L)).thenReturn(Optional.of(StudentExam.builder().id(8L).session(session()).build()));
        when(questions.existsByStudentExamId(8L)).thenReturn(true);
        assertCode(() -> service.remove(8L), ErrorCode.STUDENT_EXAM_IN_USE);
        verify(exams, never()).delete(any());
    }

    /** Không cho từ khóa NUL đi tới driver PostgreSQL thành lỗi 500. */
    @Test
    void rejectsNulSearchBeforeQuery() {
        var request = new EligibleStudentSearchRequest();
        request.setQuery("a\0b");
        assertCode(() -> service.eligible(request), ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(users);
    }

    /** Parent UPCOMING hợp lệ trong tương lai. */
    private ExamSession session() {
        var start = OffsetDateTime.now().plusDays(7);
        return ExamSession.builder().id(7L).scheduledStartAt(start).scheduledEndAt(start.plusHours(2))
                .slotDurationMinutes(20).status(ExamSessionStatus.UPCOMING).build();
    }

    /** User giả có role hiện hành. */
    private User user(long id, String role) {
        return User.builder().id(id).username("u" + id).fullName("Sinh viên " + id).role(Role.builder().code(role).build()).build();
    }

    /** Danh sách ID như client gửi, không gán owner hoặc lịch. */
    private StudentAssignmentRequest request(Long... ids) {
        var request = new StudentAssignmentRequest();
        request.setStudentIds(List.of(ids));
        return request;
    }

    /** Đối chiếu lỗi nghiệp vụ bằng enum. */
    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AppException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
