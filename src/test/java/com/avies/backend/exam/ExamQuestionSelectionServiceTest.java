package com.avies.backend.exam;

import com.avies.backend.entity.*;
import com.avies.backend.entity.enums.*;
import com.avies.backend.exception.*;
import com.avies.backend.repository.*;
import com.avies.backend.service.impl.ExamQuestionSelectionServiceImpl;
import com.avies.backend.service.support.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.avies.backend.exam.ExamQuestionTestData.row;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit test orchestration; hợp đồng transaction MANDATORY được kiểm tra riêng trên PostgreSQL. */
@ExtendWith(MockitoExtension.class)
class ExamQuestionSelectionServiceTest {
    /** Child query/lock. */ @Mock private StudentExamRepository exams;
    /** Các liên kết câu đã lưu. */ @Mock private StudentExamQuestionRepository links;
    /** Native scalar pool và khóa câu. */ @Mock private QuestionRepository questions;
    /** Khóa rubric/criteria. */ @Mock private RubricRepository rubrics;
    /** Chốt/đọc snapshot; persistence được kiểm thử riêng trên PostgreSQL. */
    @Mock private ExamSnapshotSupport snapshots;
    /** Parent locking policy. */ @Mock private ExamAccessPolicy access;
    /** Persistence context giả, không kết nối DB. */ @Mock private EntityManager em;
    /** Service dùng validator và selector thật, seed cố định. */
    private ExamQuestionSelectionServiceImpl service;
    /** Parent mặc định cần hai câu. */
    private ExamSession parent;
    /** Child mới chưa có evidence. */
    private StudentExam exam;

    /** Dựng service thật mà không nạp Spring application hoặc secrets. */
    @BeforeEach
    void prepare() {
        service = new ExamQuestionSelectionServiceImpl(exams, links, questions, access,
                new ExamQuestionEligibility(), new ExamQuestionSelector(new Random(17)), snapshots);
        ReflectionTestUtils.setField(service, "entityManager", em);
        parent = ExamSession.builder().id(7L).subject(Subject.builder().id(1L).build()).maxMainQuestions(2).build();
        exam = StudentExam.builder().id(8L).session(parent).build();
    }

    /** StudentExam chưa tồn tại không đọc pool hoặc ghi dữ liệu. */
    @Test
    void missingExamReturnsBusinessError() {
        assertCode(() -> service.selectForStart(8L), ErrorCode.STUDENT_EXAM_NOT_FOUND);
        verifyNoInteractions(questions, rubrics, links);
    }

    /** Parent mất giữa đọc child và lock trả cùng lỗi phiên thi hiện có, không ghi bộ mới. */
    @Test
    void missingParentUsesExistingSessionError() {
        when(exams.findSessionIdForRemoval(8L)).thenReturn(Optional.of(7L));
        when(access.lockForInternalExamOperation(7L)).thenThrow(new AppException(ErrorCode.EXAM_SESSION_NOT_FOUND));
        assertCode(() -> service.selectForStart(8L), ErrorCode.EXAM_SESSION_NOT_FOUND);
        verifyNoInteractions(questions, rubrics, links);
    }

    /** Không đủ distinct thì không tạo bộ thiếu hoặc gọi AI thay thế. */
    @Test
    void insufficientBankNeverPersistsPartialSet() {
        ready();
        when(questions.findSelectionPool(1L)).thenReturn(List.of(row(1, BloomLevel.APPLY)));
        assertCode(() -> service.selectForStart(8L), ErrorCode.EXAM_QUESTION_POOL_INSUFFICIENT);
        verify(links, never()).saveAllAndFlush(any());
    }

    /** Đủ câu cơ bản nhưng rubric chưa hợp lệ trả lỗi rubric rõ ràng và không chữa dữ liệu. */
    @Test
    void distinguishesInsufficientUsableRubrics() {
        ready();
        when(snapshots.captureRubric(parent)).thenThrow(new AppException(ErrorCode.EXAM_QUESTION_RUBRIC_INVALID));
        assertCode(() -> service.selectForStart(8L), ErrorCode.EXAM_QUESTION_RUBRIC_INVALID);
        verify(links, never()).saveAllAndFlush(any());
    }

    /** Bộ đủ đã lưu trả nguyên ID và questionOrder, không random hoặc đọc bank lần nữa. */
    @Test
    void persistedSetIsReturnedWithoutRerandomizationEvenAfterStart() {
        ready();
        exam.setStatus(StudentExamStatus.COMPLETED);
        var first = link(51, 3, 1); var second = link(52, 1, 2);
        when(links.findByStudentExamIdOrderByQuestionOrderAscIdAsc(8L)).thenReturn(List.of(first, second));
        var result = service.selectForStart(8L);
        assertThat(result).extracting(r -> r.getQuestionId()).containsExactly(3L, 1L);
        assertThat(result).extracting(r -> r.getStudentExamQuestionId()).containsExactly(51L, 52L);
        verifyNoInteractions(questions, rubrics);
        verify(links, never()).saveAllAndFlush(any());
    }

    /** Bộ đã lưu sai số lượng hoặc thiếu thứ tự không bị xóa để chọn lại. */
    @Test
    void existingIncompleteOrGappedSetIsNeverReplaced() {
        ready();
        when(links.findByStudentExamIdOrderByQuestionOrderAscIdAsc(8L)).thenReturn(List.of(link(51, 1, 1)));
        assertCode(() -> service.selectForStart(8L), ErrorCode.EXAM_QUESTION_SET_INCONSISTENT);
        when(links.findByStudentExamIdOrderByQuestionOrderAscIdAsc(8L)).thenReturn(List.of(link(51, 1, 1), link(52, 2, 3)));
        assertCode(() -> service.selectForStart(8L), ErrorCode.EXAM_QUESTION_SET_INCONSISTENT);
        verifyNoInteractions(questions, rubrics);
        verify(links, never()).saveAllAndFlush(any());
    }

    /** Câu đổi hoặc bị xóa giữa chọn và lock làm toàn workflow thất bại, không chọn bù. */
    @Test
    void missingSelectedQuestionDuringLockIsConsistencyConflict() {
        ready();
        when(questions.findSelectionPool(1L)).thenReturn(List.of(row(1, BloomLevel.APPLY), row(2, BloomLevel.ANALYZE)));
        assertCode(() -> service.selectForStart(8L), ErrorCode.EXAM_QUESTION_ELIGIBILITY_CHANGED);
        verify(links, never()).saveAllAndFlush(any());
    }

    /** Re-read scalar phát hiện version đã đổi dù eligibility nhìn bề ngoài vẫn hợp lệ. */
    @Test
    void changedVersionAfterLockDoesNotPersist() {
        ready();
        var initial = List.of(row(1, BloomLevel.APPLY), row(2, BloomLevel.ANALYZE));
        when(questions.findSelectionPool(1L)).thenReturn(new ArrayList<>(initial));
        when(questions.lockForSelection(List.of(1L, 2L))).thenReturn(List.of(1L, 2L));
        var changed = row(1, BloomLevel.APPLY); changed.setQuestionVersion(1L);
        when(questions.readSelectionRows(List.of(1L, 2L))).thenReturn(List.of(changed, initial.get(1)));
        assertCode(() -> service.selectForStart(8L), ErrorCode.EXAM_QUESTION_ELIGIBILITY_CHANGED);
        verify(links, never()).saveAllAndFlush(any());
    }

    /** Native constraint exception được đổi thành AppException an toàn, không mang SQL/đáp án. */
    @Test
    void databaseFailureIsMappedWithoutInternalMessage() {
        ready();
        when(questions.findSelectionPool(1L)).thenThrow(new DataIntegrityViolationException("SQL reference-answer private"));
        assertCode(() -> service.selectForStart(8L), ErrorCode.EXAM_QUESTION_SELECTION_CONFLICT);
    }

    /** Lỗi SQL ngoài nhóm cạnh tranh cũng không thoát ra với message driver chứa dữ liệu nội bộ. */
    @Test
    void unexpectedDatabaseFailureHasGenericSafeMessage() {
        ready();
        when(questions.findSelectionPool(1L)).thenThrow(new org.springframework.dao.InvalidDataAccessResourceUsageException("SQL private reference-answer"));
        assertThatThrownBy(() -> service.selectForStart(8L)).isInstanceOfSatisfying(AppException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNCATEGORIZED_EXCEPTION);
            assertThat(exception.getMessage()).doesNotContain("SQL", "private", "reference-answer");
        });
    }

    /** Parent đã hoàn tất không được tạo bộ mới. */
    @Test
    void rejectsNewSelectionForCompletedSession() {
        ready(); parent.setStatus(ExamSessionStatus.COMPLETED);
        assertCode(() -> service.selectForStart(8L), ErrorCode.EXAM_QUESTION_SELECTION_NOT_ALLOWED);
        verifyNoInteractions(questions, rubrics);
    }

    /** Khóa và đọc child thật được stub đúng scope, không giả lập authorization của Start tương lai. */
    private void ready() {
        var criterion = new com.avies.backend.dto.snapshot.ExamRubricSnapshot.Criterion(1001L,
                "Tiêu chí chung", java.math.BigDecimal.TEN, false, 1);
        lenient().when(snapshots.captureRubric(parent)).thenReturn(new com.avies.backend.dto.snapshot.ExamRubricSnapshot(
                1, 101L, 1L, "Rubric môn", null, null, List.of(criterion)));
        when(exams.findSessionIdForRemoval(8L)).thenReturn(Optional.of(7L));
        when(access.lockForInternalExamOperation(7L)).thenReturn(parent);
        when(exams.lockForRemoval(8L)).thenReturn(Optional.of(8L));
        when(exams.findById(8L)).thenReturn(Optional.of(exam));
    }

    /** Link đã lưu không cần nội dung Question/Rubric. */
    private StudentExamQuestion link(long id, long questionId, int order) {
        return StudentExamQuestion.builder().id(id).studentExam(exam).question(Question.builder().id(questionId).build())
                .questionOrder(order).build();
    }

    /** ErrorCode ổn định, message không chứa exception driver. */
    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AppException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
