package com.avies.backend.service.impl;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.*;
import com.avies.backend.entity.*;
import com.avies.backend.entity.enums.ExamSessionStatus;
import com.avies.backend.entity.enums.StudentExamStatus;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.*;
import com.avies.backend.service.StudentExamService;
import com.avies.backend.service.support.ExamAccessPolicy;
import com.avies.backend.service.support.ExamSlotPlanner;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Phân công nguyên tử dưới khóa parent; giữ lịch cũ và giới hạn dữ liệu công khai trong DTO. */
@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentExamServiceImpl implements StudentExamService {
    /** Query role STUDENT và khóa user đích trong transaction gán. */
    private final UserRepository users;
    /** Nguồn bài thi, lịch và kiểm tra sử dụng. */
    private final StudentExamRepository exams;
    /** Có câu hỏi đã gán thì không được thu hồi để tránh mất transcript qua cascade. */
    private final StudentExamQuestionRepository questions;
    /** Dùng chung kiểm tra quyền và thứ tự khóa với CRUD phiên thi. */
    private final ExamAccessPolicy access;
    /** Thuật toán slot thuần, có thể kiểm thử độc lập không cần database. */
    private final ExamSlotPlanner planner;
    /** Refresh user/child sau khi lấy khóa, không dựa trên persistence context đã cũ. */
    @PersistenceContext
    private EntityManager entityManager;

    /** Chỉ role STUDENT hiện tại được lọc và đếm; escape wildcard để tìm theo chuỗi literal. */
    @Override
    public PageResponse<EligibleStudentResponse> eligible(EligibleStudentSearchRequest request) {
        access.currentUser("LECTURER");
        String query = request.getQuery() == null ? "" : request.getQuery().strip().toLowerCase(Locale.ROOT);
        if (query.indexOf('\0') >= 0) throw new AppException(ErrorCode.INVALID_REQUEST);
        String pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return page(users.searchEligibleStudents(pattern, pageable(request)),
                user -> EligibleStudentResponse.builder().id(user.getId()).username(user.getUsername()).fullName(user.getFullName()).build());
    }

    /** Validate toàn bộ batch trước insert; request lặp không tạo bài hoặc đổi slot đã cấp. */
    @Override
    @Transactional(timeout = 10)
    public StudentAssignmentResponse assign(Long sessionId, StudentAssignmentRequest request) {
        ExamSession session = access.ownedForWrite(sessionId);
        requireUpcoming(session);
        if (exams.hasStartedExam(sessionId, StudentExamStatus.ASSIGNED))
            throw new AppException(ErrorCode.EXAM_SESSION_IN_USE);
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(request.getStudentIds()));
        if (users.lockStudentsForAssignment(ids).size() != ids.size())
            throw new AppException(ErrorCode.USER_NOT_EXISTED);
        List<User> chosen = users.findAllById(ids);
        Map<Long, User> byId = new HashMap<>();
        for (User student : chosen) {
            entityManager.refresh(student);
            if (student.getRole() == null || !"STUDENT".equals(student.getRole().getCode()))
                throw new AppException(ErrorCode.ASSIGNMENT_NOT_STUDENT);
            byId.put(student.getId(), student);
        }
        List<StudentExam> existing = exams.findBySessionIdOrderByIdAsc(sessionId);
        Map<Long, StudentExam> already = existing.stream().collect(Collectors.toMap(e -> e.getStudent().getId(), Function.identity()));
        List<Long> newIds = ids.stream().filter(id -> !already.containsKey(id)).toList();
        var slots = planner.plan(session, existing, newIds.size());
        List<StudentExam> created = new ArrayList<>();
        for (int i = 0; i < newIds.size(); i++) {
            var slot = slots.get(i);
            created.add(StudentExam.builder().session(session).student(byId.get(newIds.get(i)))
                    .status(StudentExamStatus.ASSIGNED).slotOrder(slot.order())
                    .scheduledStartAt(slot.start()).scheduledEndAt(slot.end()).build());
        }
        exams.saveAllAndFlush(created);
        List<StudentExamAssignmentResponse> unchanged = ids.stream().filter(already::containsKey)
                .map(already::get).map(this::assignmentResponse).toList();
        return StudentAssignmentResponse.builder().sessionId(sessionId)
                .created(created.stream().map(this::assignmentResponse).toList()).unchanged(unchanged).build();
    }

    /** Phân trang chỉ sau khi đã xác minh owner/admin; không trả email, điểm, câu hỏi hoặc transcript. */
    @Override
    public PageResponse<StudentExamAssignmentResponse> assigned(Long sessionId, ExamAssignmentPageRequest request) {
        access.readableSession(sessionId);
        return page(exams.searchAssignments(sessionId, pageable(request)), this::assignmentResponse);
    }

    /** Khóa parent rồi child trước kiểm tra evidence để không cascade xóa dữ liệu vừa được gắn. */
    @Override
    @Transactional(timeout = 10)
    public void remove(Long id) {
        access.currentUser("LECTURER");
        Long parentId = exams.findSessionIdForRemoval(id)
                .orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        ExamSession parent;
        try {
            parent = access.ownedForWrite(parentId);
        } catch (AppException exception) {
            if (exception.getErrorCode() == ErrorCode.EXAM_SESSION_NOT_FOUND)
                throw new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND);
            throw exception;
        }
        requireUpcoming(parent);
        exams.lockForRemoval(id).orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        StudentExam exam = exams.findById(id).orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        entityManager.refresh(exam);
        if (!Objects.equals(parentId, exam.getSession().getId()))
            throw new AppException(ErrorCode.EXAM_SESSION_CONFLICT);
        if (exam.getStatus() != StudentExamStatus.ASSIGNED || exam.getStartedAt() != null || exam.getCompletedAt() != null
                || exam.getAiSuggestedScore() != null || exam.getFinalScore() != null || exam.getAiFeedback() != null
                || exam.getAudioRecordUrl() != null || questions.existsByStudentExamId(id))
            throw new AppException(ErrorCode.STUDENT_EXAM_IN_USE);
        exams.delete(exam);
        exams.flush();
    }

    /** Scope studentId lấy từ DB theo JWT trước query/count; bao gồm lịch sử đã thi của chính mình. */
    @Override
    public PageResponse<MyStudentExamResponse> mine(ExamAssignmentPageRequest request) {
        User actor = access.currentUser("STUDENT");
        return page(exams.searchMine(actor.getId(), pageable(request)), this::myResponse);
    }

    /** Phiên bắt đầu hoặc kết thúc không được nhận thêm hay thu hồi sinh viên bằng API này. */
    private void requireUpcoming(ExamSession session) {
        if (session.getStatus() != ExamSessionStatus.UPCOMING)
            throw new AppException(ErrorCode.EXAM_SESSION_NOT_EDITABLE);
    }

    /** DTO lịch theo phiên, không serialize entity hoặc dữ liệu thi nhạy cảm. */
    private StudentExamAssignmentResponse assignmentResponse(StudentExam exam) {
        return StudentExamAssignmentResponse.builder().id(exam.getId()).studentId(exam.getStudent().getId())
                .studentUsername(exam.getStudent().getUsername()).studentFullName(exam.getStudent().getFullName())
                .status(exam.getStatus()).slotOrder(exam.getSlotOrder())
                .scheduledStartAt(exam.getScheduledStartAt()).scheduledEndAt(exam.getScheduledEndAt()).build();
    }

    /** Lịch cá nhân không chứa thông tin các sinh viên khác hoặc nội dung chấm thi. */
    private MyStudentExamResponse myResponse(StudentExam exam) {
        ExamSession session = exam.getSession();
        Subject subject = session.getSubject();
        return MyStudentExamResponse.builder().id(exam.getId()).sessionId(session.getId()).sessionTitle(session.getTitle())
                .subjectId(subject.getId()).subjectCode(subject.getCode()).subjectName(subject.getName())
                .sessionStatus(session.getStatus()).status(exam.getStatus()).slotOrder(exam.getSlotOrder())
                .scheduledStartAt(exam.getScheduledStartAt()).scheduledEndAt(exam.getScheduledEndAt()).build();
    }

    /** Chuyển page request đã Bean Validation sang kiểu nội bộ của Spring Data. */
    private PageRequest pageable(ExamAssignmentPageRequest request) {
        return PageRequest.of(request.getPage(), request.getSize());
    }

    /** Giữ cùng envelope phân trang cho ba API đọc. */
    private <T, R> PageResponse<R> page(Page<T> result, Function<T, R> mapper) {
        return PageResponse.<R>builder().items(result.getContent().stream().map(mapper).toList())
                .page(result.getNumber()).size(result.getSize()).totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages()).build();
    }
}
