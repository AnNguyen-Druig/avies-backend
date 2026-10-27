package com.avies.backend.service.impl;

import com.avies.backend.dto.response.*;
import com.avies.backend.dto.snapshot.ExamGradingContext;
import com.avies.backend.entity.*;
import com.avies.backend.entity.enums.*;
import com.avies.backend.exception.*;
import com.avies.backend.repository.*;
import com.avies.backend.service.*;
import com.avies.backend.service.support.ExamAccessPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import java.sql.SQLException;

/** Vòng đời thi dùng chung giao thức khóa với CRUD/phân công/chọn câu, không gọi AI trong transaction. */
@Service
@Validated
@RequiredArgsConstructor
@Transactional(timeout = 10)
public class ExamLifecycleServiceImpl implements ExamLifecycleService {
    /** Kiểm tra JWT/role hiện hành và khóa owner → môn → phiên. */
    private final ExamAccessPolicy access;
    /** Nguồn dữ liệu và khóa lượt thi. */
    private final StudentExamRepository exams;
    /** Khóa user sinh viên sau parent để bảo vệ role hiện hành. */
    private final UserRepository users;
    /** Kiểm tra phân công của giảng viên khi bắt đầu bài mới. */
    private final LecturerSubjectAssignmentRepository assignments;
    /** Khóa bộ câu trước khi kiểm tra bằng chứng trả lời. */
    private final StudentExamQuestionRepository questions;
    /** Thuật toán chọn câu/snapshot tham gia transaction hiện tại. */
    private final ExamQuestionSelectionService selection;
    /** Đọc lịch sử, không dùng đáp án hoặc rubric đang chỉnh sửa trong ngân hàng. */
    private final ExamSnapshotService snapshots;
    /** Đọc câu trả lời thực sự đã lưu bởi Feature 3. */
    private final ExamTranscriptRepository transcripts;
    /** Đồng hồ máy chủ có thể cố định trong test. */
    private final Clock clock;
    /** Refresh dưới khóa để không quyết định trên entity đã cũ trong persistence context. */
    @PersistenceContext
    private EntityManager entityManager;

    /** Chỉ đổi trạng thái sau khi bộ câu và snapshot đã hợp lệ; mọi lỗi rollback toàn bộ. */
    @Override
    public StartedExamResponse start(Long studentExamId) {
        return command(() -> startLocked(studentExamId));
    }

    /** Phần ghi Start luôn chạy trong transaction do phương thức public mở. */
    private StartedExamResponse startLocked(Long studentExamId) {
        StudentExam exam = ownLockedAttempt(studentExamId);
        ExamSession session = exam.getSession();
        requireActiveSession(session);
        requireSchedule(exam);
        requireWithinSlot(exam, now());
        if (exam.getStatus() != StudentExamStatus.ASSIGNED && exam.getStatus() != StudentExamStatus.IN_PROGRESS)
            throw new AppException(ErrorCode.EXAM_LIFECYCLE_NOT_ALLOWED);
        if (exam.getStatus() == StudentExamStatus.ASSIGNED) {
            requireUnstartedEvidence(exam);
            entityManager.refresh(session.getLecturer());
            if (!"LECTURER".equals(session.getLecturer().getRole().getCode())
                    || !assignments.existsByLecturerIdAndSubjectId(session.getLecturer().getId(), session.getSubject().getId()))
                throw new AppException(ErrorCode.SUBJECT_NOT_ASSIGNED);
        } else {
            if (session.getStatus() != ExamSessionStatus.ONGOING) throw new AppException(ErrorCode.EXAM_LIFECYCLE_INCONSISTENT);
            requireStartedEvidence(exam, now());
        }
        selection.selectForStart(exam.getId());
        ExamGradingContext context = snapshots.loadForGrading(exam.getId());
        OffsetDateTime timestamp = now();
        requireWithinSlot(exam, timestamp);
        if (exam.getStatus() == StudentExamStatus.ASSIGNED) {
            exam.setStartedAt(timestamp);
            exam.setStatus(StudentExamStatus.IN_PROGRESS);
            session.setStatus(ExamSessionStatus.ONGOING);
        }
        entityManager.flush();
        return new StartedExamResponse(response(exam), context.questions().stream().map(question ->
                new StartedExamResponse.Question(question.studentExamQuestionId(), question.questionOrder(),
                        question.content(), question.answerTimeLimitSeconds())).toList());
    }

    /** Không nhận bài làm/điểm từ client; chỉ hoàn thành trong slot khi đủ câu trả lời đã được lưu. */
    @Override
    public ExamAttemptResponse finish(Long studentExamId) {
        return command(() -> finishLocked(studentExamId));
    }

    /** Chốt trạng thái hoàn thành sau khi đọc bằng chứng dưới cùng bộ khóa. */
    private ExamAttemptResponse finishLocked(Long studentExamId) {
        StudentExam exam = ownLockedAttempt(studentExamId);
        if (exam.getStatus() == StudentExamStatus.COMPLETED) {
            if (exam.getStartedAt() == null || exam.getCompletedAt() == null || exam.getCompletedAt().isBefore(exam.getStartedAt()))
                throw new AppException(ErrorCode.EXAM_LIFECYCLE_INCONSISTENT);
            return response(exam);
        }
        if (exam.getStatus() != StudentExamStatus.IN_PROGRESS || exam.getSession().getStatus() != ExamSessionStatus.ONGOING)
            throw new AppException(ErrorCode.EXAM_LIFECYCLE_NOT_ALLOWED);
        if (exam.getStartedAt() == null || exam.getCompletedAt() != null)
            throw new AppException(ErrorCode.EXAM_LIFECYCLE_INCONSISTENT);
        requireSchedule(exam);
        OffsetDateTime timestamp = now();
        requireWithinSlot(exam, timestamp);
        requireStartedEvidence(exam, timestamp);
        questions.lockPersistedSet(exam.getId());
        ExamGradingContext context = snapshots.loadForGrading(exam.getId());
        Set<Long> answered = transcripts.lockSavedAnswers(exam.getId()).stream()
                .filter(row -> row.content() != null && !row.content().isBlank() && row.createdAt() != null
                        && !row.createdAt().isBefore(exam.getStartedAt()) && !row.createdAt().isAfter(timestamp))
                .map(row -> row.studentExamQuestionId()).collect(Collectors.toSet());
        if (!context.questions().stream().allMatch(question -> answered.contains(question.studentExamQuestionId())))
            throw new AppException(ErrorCode.EXAM_ANSWERS_NOT_READY);
        OffsetDateTime completedAt = now();
        requireWithinSlot(exam, completedAt);
        if (completedAt.isBefore(exam.getStartedAt())) throw new AppException(ErrorCode.EXAM_LIFECYCLE_INCONSISTENT);
        exam.setCompletedAt(completedAt);
        exam.setStatus(StudentExamStatus.COMPLETED);
        entityManager.flush();
        return response(exam);
    }

    /** Đóng nguyên tử sau hạn phiên, không xóa bằng chứng hoặc tạo điểm/giờ hoàn thành giả. */
    @Override
    public ClosedExamSessionResponse close(Long sessionId) {
        return command(() -> closeLocked(sessionId));
    }

    /** Phân loại toàn bộ child và đóng parent trong cùng transaction ghi. */
    private ClosedExamSessionResponse closeLocked(Long sessionId) {
        ExamSession session = access.ownedForWrite(sessionId);
        exams.lockForSessionClosure(sessionId);
        List<StudentExam> rows = exams.findBySessionIdOrderByIdAsc(sessionId);
        rows.forEach(entityManager::refresh);
        if (session.getStatus() == ExamSessionStatus.COMPLETED) {
            if (rows.stream().anyMatch(row -> row.getStatus() == StudentExamStatus.ASSIGNED || row.getStatus() == StudentExamStatus.IN_PROGRESS))
                throw new AppException(ErrorCode.EXAM_LIFECYCLE_INCONSISTENT);
            return closedResponse(session, rows);
        }
        if (session.getScheduledStartAt() == null || session.getScheduledEndAt() == null
                || !session.getScheduledEndAt().isAfter(session.getScheduledStartAt()))
            throw new AppException(ErrorCode.EXAM_LIFECYCLE_INVALID_SCHEDULE);
        OffsetDateTime timestamp = now();
        if (timestamp.isBefore(session.getScheduledEndAt())) throw new AppException(ErrorCode.EXAM_SESSION_NOT_ENDED);
        for (StudentExam exam : rows) {
            if (exam.getStatus() == StudentExamStatus.ASSIGNED) {
                requireUnstartedEvidence(exam);
                exam.setStatus(StudentExamStatus.ABSENT);
            } else if (exam.getStatus() == StudentExamStatus.IN_PROGRESS) {
                requireStartedEvidence(exam, timestamp);
                exam.setStatus(StudentExamStatus.EXPIRED);
            }
        }
        session.setStatus(ExamSessionStatus.COMPLETED);
        entityManager.flush();
        return closedResponse(session, rows);
    }

    /** Che lượt người khác bằng 404, rồi khóa owner → môn → phiên → student → lượt theo giao thức chung. */
    private StudentExam ownLockedAttempt(Long id) {
        User actor = access.currentUser("STUDENT");
        StudentExam exam = exams.findById(id).orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        if (!Objects.equals(exam.getStudent().getId(), actor.getId())) throw new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND);
        Long parentId = exam.getSession().getId();
        access.lockForInternalExamOperation(parentId);
        if (users.lockStudentsForAssignment(List.of(actor.getId())).isEmpty()) throw new AppException(ErrorCode.UNAUTHENTICATED);
        entityManager.refresh(actor);
        access.currentUser("STUDENT");
        exams.lockForRemoval(id).orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        entityManager.refresh(exam);
        if (!Objects.equals(exam.getStudent().getId(), actor.getId()) || !Objects.equals(exam.getSession().getId(), parentId))
            throw new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND);
        return exam;
    }

    /** Không khởi động lại phiên đã đóng; phiên chưa có ai bắt đầu hoặc đang diễn ra đều hợp lệ. */
    private void requireActiveSession(ExamSession session) {
        if (session.getStatus() != ExamSessionStatus.UPCOMING && session.getStatus() != ExamSessionStatus.ONGOING)
            throw new AppException(ErrorCode.EXAM_LIFECYCLE_NOT_ALLOWED);
    }

    /** Giữ lịch slot khớp cấu hình phiên; không dùng createdAt để chế tạo lịch legacy. */
    private void requireSchedule(StudentExam exam) {
        ExamSession session = exam.getSession();
        if (session.getScheduledStartAt() == null || session.getScheduledEndAt() == null
                || !session.getScheduledEndAt().isAfter(session.getScheduledStartAt())
                || session.getSlotDurationMinutes() == null || session.getSlotDurationMinutes() <= 0
                || exam.getSlotOrder() == null || exam.getSlotOrder() <= 0
                || exam.getScheduledStartAt() == null || exam.getScheduledEndAt() == null)
            throw new AppException(ErrorCode.EXAM_LIFECYCLE_INVALID_SCHEDULE);
        long minutes = session.getSlotDurationMinutes();
        long capacity = Duration.between(session.getScheduledStartAt(), session.getScheduledEndAt()).getSeconds() / (minutes * 60L);
        if (exam.getSlotOrder() > capacity
                || !exam.getScheduledStartAt().isEqual(session.getScheduledStartAt().plusMinutes((exam.getSlotOrder() - 1L) * minutes))
                || !exam.getScheduledEndAt().isEqual(exam.getScheduledStartAt().plusMinutes(minutes)))
            throw new AppException(ErrorCode.EXAM_LIFECYCLE_INVALID_SCHEDULE);
    }

    /** Khoảng nửa mở: đến đúng giờ bắt đầu được vào, đến đúng hạn kết thúc đã hết giờ. */
    private void requireWithinSlot(StudentExam exam, OffsetDateTime timestamp) {
        if (timestamp.isBefore(exam.getScheduledStartAt()) || !timestamp.isBefore(exam.getScheduledEndAt()))
            throw new AppException(ErrorCode.EXAM_OUTSIDE_SLOT);
    }

    /** Không coi trạng thái ASSIGNED có timestamp/điểm/audio là một lượt chưa thực hiện. */
    private void requireUnstartedEvidence(StudentExam exam) {
        if (exam.getStartedAt() != null || exam.getCompletedAt() != null || exam.getAiSuggestedScore() != null
                || exam.getFinalScore() != null || exam.getAiFeedback() != null || exam.getAudioRecordUrl() != null
                || transcripts.hasStudentAnswer(exam.getId()))
            throw new AppException(ErrorCode.EXAM_LIFECYCLE_INCONSISTENT);
    }

    /** Bằng chứng bắt đầu phải có thật, không ở tương lai hoặc nằm ngoài slot đã phân công. */
    private void requireStartedEvidence(StudentExam exam, OffsetDateTime timestamp) {
        if (exam.getStartedAt() == null || exam.getCompletedAt() != null || exam.getStartedAt().isAfter(timestamp)
                || (exam.getScheduledStartAt() != null && exam.getStartedAt().isBefore(exam.getScheduledStartAt()))
                || (exam.getScheduledEndAt() != null && !exam.getStartedAt().isBefore(exam.getScheduledEndAt())))
            throw new AppException(ErrorCode.EXAM_LIFECYCLE_INCONSISTENT);
    }

    /** Giờ máy chủ theo độ chính xác microsecond của PostgreSQL để retry không lệch timestamp vì làm tròn. */
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** Chuẩn hóa SQLSTATE khóa/ràng buộc cho cả JPA và JDBC, không biến lỗi lập trình thành xung đột giả. */
    private <T> T command(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException | PersistenceException exception) {
            Throwable cause = exception;
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            while (cause != null && visited.add(cause)) {
                if (cause instanceof SQLException sql) {
                    String state = sql.getSQLState();
                    if (state != null && (state.startsWith("23") || Set.of("55P03", "40P01", "40001", "57014").contains(state)))
                        throw new AppException(ErrorCode.EXAM_SESSION_CONFLICT);
                }
                cause = cause.getCause();
            }
            throw exception;
        }
    }

    /** Dữ liệu tối thiểu để FE cập nhật trạng thái và hạn thi. */
    private ExamAttemptResponse response(StudentExam exam) {
        return new ExamAttemptResponse(exam.getId(), exam.getSession().getId(), exam.getStatus(),
                exam.getSession().getStatus(), exam.getStartedAt(), exam.getCompletedAt(), exam.getScheduledEndAt());
    }

    /** Tổng số cuối cùng ổn định khi gọi Close lại sau commit thành công. */
    private ClosedExamSessionResponse closedResponse(ExamSession session, List<StudentExam> rows) {
        return new ClosedExamSessionResponse(session.getId(), session.getStatus(),
                rows.stream().filter(row -> row.getStatus() == StudentExamStatus.COMPLETED).count(),
                rows.stream().filter(row -> row.getStatus() == StudentExamStatus.ABSENT).count(),
                rows.stream().filter(row -> row.getStatus() == StudentExamStatus.EXPIRED).count());
    }
}
