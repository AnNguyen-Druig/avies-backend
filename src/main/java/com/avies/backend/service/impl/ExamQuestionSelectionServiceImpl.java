package com.avies.backend.service.impl;

import com.avies.backend.dto.response.SelectedMainQuestion;
import com.avies.backend.entity.*;
import com.avies.backend.entity.enums.*;
import com.avies.backend.exception.*;
import com.avies.backend.repository.*;
import com.avies.backend.service.ExamQuestionSelectionService;
import com.avies.backend.service.support.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;

import java.util.*;
import java.util.stream.IntStream;

/**
 * Service nội bộ nguyên tử; không mở REST, không chuyển trạng thái và không gửi nội dung chấm thi.
 */
@Service
@Validated
@Slf4j
@RequiredArgsConstructor
public class ExamQuestionSelectionServiceImpl implements ExamQuestionSelectionService {

    private final StudentExamRepository studentExams;
    private final StudentExamQuestionRepository selections;
    private final QuestionRepository questions;
    private final RubricRepository rubrics;
    private final ExamAccessPolicy access;
    private final ExamQuestionEligibility eligibility;
    private final ExamQuestionSelector selector;

    /**
     * Refresh child và link dưới khóa, không clear persistence context của caller.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Phải tham gia transaction Start của caller để lưu bộ và bắt đầu thi có thể commit/rollback cùng nhau.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SelectedMainQuestion> selectForStart(Long studentExamId) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("Selection cần transaction ghi của workflow gọi.");
        try {
            return selectLocked(studentExamId);
        } catch (AppException exception) {
            if (exception.getErrorCode() == ErrorCode.EXAM_SESSION_CONFLICT)
                throw new AppException(ErrorCode.EXAM_QUESTION_SELECTION_CONFLICT);
            throw exception;
        } catch (ConcurrencyFailureException | DataIntegrityViolationException | QueryTimeoutException |
                 TransactionTimedOutException | EntityNotFoundException exception) {
            throw new AppException(ErrorCode.EXAM_QUESTION_SELECTION_CONFLICT);
        } catch (DataAccessException | PersistenceException exception) {
            log.error("Không thể chuẩn bị bộ câu hỏi", exception);
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
    }

    /**
     * Khóa parent rồi child; bộ đầy đủ cũ là kết quả cuối cùng, không kiểm tra lại eligibility để bốc bộ khác.
     */
    private List<SelectedMainQuestion> selectLocked(Long studentExamId) {
        Long parentId = studentExams.findSessionIdForRemoval(studentExamId).orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        ExamSession parent = access.lockForInternalExamOperation(parentId);
        studentExams.lockForRemoval(studentExamId).orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        StudentExam exam = studentExams.findById(studentExamId).orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        entityManager.refresh(exam);
        if (!Objects.equals(parentId, exam.getSession().getId()))
            throw new AppException(ErrorCode.EXAM_QUESTION_SELECTION_CONFLICT);
        Integer count = parent.getMaxMainQuestions();
        if (count == null || count < 1) throw new AppException(ErrorCode.EXAM_QUESTION_SELECTION_NOT_ALLOWED);
        selections.lockPersistedSet(studentExamId);
        List<StudentExamQuestion> existing = selections.findByStudentExamIdOrderByQuestionOrderAscIdAsc(studentExamId);
        existing.forEach(entityManager::refresh);
        if (!existing.isEmpty()) {
            requireComplete(existing, count);
            return existing.stream().map(this::response).toList();
        }
        requireReadyForNewSet(parent, exam);
        var initial = eligibility.inspect(questions.findSelectionPool(parent.getSubject().getId()), parent.getSubject().getId());
        if (initial.candidates().size() < count) {
            throw new AppException(initial.basicCount() >= count ? ErrorCode.EXAM_QUESTION_RUBRIC_INVALID : ErrorCode.EXAM_QUESTION_POOL_INSUFFICIENT);
        }
        var history = selections.findSelectionHistory(parentId, studentExamId).stream().map(row -> new ExamQuestionSelector.Use(row.getStudentExam().getId(), row.getQuestion().getId(), row.getStudentExam().getStartedAt(), row.getStudentExam().getCompletedAt(), row.getId())).toList();
        var chosen = selector.choose(new ArrayList<>(initial.candidates().values()), history, count);
        lockAndRevalidate(chosen, parent.getSubject().getId());
        List<StudentExamQuestion> created = IntStream.range(0, count).mapToObj(index -> StudentExamQuestion.builder().studentExam(exam).question(entityManager.getReference(Question.class, chosen.get(index).id())).questionOrder(index + 1).build()).toList();
        selections.saveAllAndFlush(created);
        return created.stream().map(this::response).toList();
    }

    /**
     * Mọi thay đổi nội dung hoặc rubric giữa hai lần đọc đều khiến caller rollback, không chọn bù âm thầm.
     */
    private void lockAndRevalidate(List<ExamQuestionEligibility.Candidate> chosen, Long subjectId) {
        List<Long> ids = chosen.stream().map(ExamQuestionEligibility.Candidate::id).sorted().toList();
        if (questions.lockForSelection(ids).size() != ids.size())
            throw new AppException(ErrorCode.EXAM_QUESTION_ELIGIBILITY_CHANGED);
        List<Long> rubricIds = rubrics.lockForQuestionSelection(ids);
        if (rubricIds.size() != ids.size()) throw new AppException(ErrorCode.EXAM_QUESTION_RUBRIC_INVALID);
        rubrics.lockCriteriaForSelection(rubricIds);
        var current = eligibility.inspect(questions.readSelectionRows(ids), subjectId);
        if (current.candidates().size() != ids.size()) {
            throw new AppException(current.basicCount() == ids.size() ? ErrorCode.EXAM_QUESTION_RUBRIC_INVALID : ErrorCode.EXAM_QUESTION_ELIGIBILITY_CHANGED);
        }
        for (var candidate : chosen) {
            if (!candidate.equals(current.candidates().get(candidate.id())))
                throw new AppException(ErrorCode.EXAM_QUESTION_ELIGIBILITY_CHANGED);
        }
    }

    /**
     * Không bổ sung hoặc thay bộ thiếu/thừa, trùng câu hay thiếu thứ tự, kể cả chưa có timestamp bắt đầu.
     */
    private void requireComplete(List<StudentExamQuestion> existing, int expected) {
        if (existing.size() != expected || existing.stream().map(row -> row.getQuestion().getId()).distinct().count() != expected)
            throw new AppException(ErrorCode.EXAM_QUESTION_SET_INCONSISTENT);
        for (int i = 0; i < expected; i++) {
            if (!Objects.equals(existing.get(i).getQuestionOrder(), i + 1))
                throw new AppException(ErrorCode.EXAM_QUESTION_SET_INCONSISTENT);
        }
    }

    /**
     * UPCOMING hoặc ONGOING đều có thể chuẩn bị bài ASSIGNED; caller Start chịu trách nhiệm kiểm tra giờ thi.
     */
    private void requireReadyForNewSet(ExamSession parent, StudentExam exam) {
        if ((parent.getStatus() != ExamSessionStatus.UPCOMING && parent.getStatus() != ExamSessionStatus.ONGOING) || exam.getStatus() != StudentExamStatus.ASSIGNED || exam.getStartedAt() != null || exam.getCompletedAt() != null || exam.getAiSuggestedScore() != null || exam.getFinalScore() != null || exam.getAiFeedback() != null || exam.getAudioRecordUrl() != null)
            throw new AppException(ErrorCode.EXAM_QUESTION_SELECTION_NOT_ALLOWED);
    }

    /**
     * Chỉ trả ID và thứ tự đã lưu; không serialize Question hoặc Rubric.
     */
    private SelectedMainQuestion response(StudentExamQuestion row) {
        return SelectedMainQuestion.builder().studentExamQuestionId(row.getId()).questionId(row.getQuestion().getId()).questionOrder(row.getQuestionOrder()).build();
    }
}
