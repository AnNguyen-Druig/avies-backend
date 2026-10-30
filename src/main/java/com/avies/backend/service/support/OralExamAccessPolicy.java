package com.avies.backend.service.support;

import com.avies.backend.entity.StudentExam;
import com.avies.backend.exception.*;
import com.avies.backend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;

/** Cùng thứ tự khóa owner → môn → phiên → student → lượt → bộ câu với Feature 2. */
@Component
@RequiredArgsConstructor
public class OralExamAccessPolicy {
    /** Chính sách JWT/role và khóa parent đã có. */ private final ExamAccessPolicy access;
    /** Lượt thi dùng chung, không tạo bản sao. */ private final StudentExamRepository exams;
    /** Khóa role của student trước khi sửa child. */ private final UserRepository users;
    /** Bộ snapshot phải ổn định trong mọi thao tác hội thoại. */ private final StudentExamQuestionRepository questions;
    /** Refresh sau khi khóa để không dùng trạng thái cũ trong persistence context. */
    @PersistenceContext private EntityManager entityManager;

    /** Endpoint Student chỉ được điều khiển lượt của chính mình, che lượt người khác bằng 404. */
    public StudentExam owned(long id) {
        var actor = access.currentUser("STUDENT");
        var exam = exams.findById(id).orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        if (!Objects.equals(exam.getStudent().getId(), actor.getId())) throw new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND);
        exam = internal(id);
        entityManager.refresh(actor); access.currentUser("STUDENT");
        if (!Objects.equals(exam.getStudent().getId(), actor.getId())) throw new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND);
        return exam;
    }

    /** Poll/lease chỉ đọc quyền sở hữu, không giành khóa ghi owner/phiên/bộ câu của worker. */
    public StudentExam readOwned(long id) {
        var actor=access.currentUser("STUDENT");
        var exam=exams.findById(id).orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        if (!Objects.equals(exam.getStudent().getId(),actor.getId())) throw new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND);
        return exam;
    }

    /** Chỉ worker nội bộ sử dụng; không có endpoint cho client gọi đường bỏ qua JWT này. */
    public StudentExam internal(long id) {
        var exam = exams.findById(id).orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        long sessionId = exam.getSession().getId(); long studentId = exam.getStudent().getId();
        access.lockForInternalExamOperation(sessionId);
        if (users.lockStudentsForAssignment(List.of(studentId)).isEmpty()) throw new AppException(ErrorCode.EXAM_SESSION_CONFLICT);
        exams.lockForRemoval(id).orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        entityManager.refresh(exam);
        if (!Objects.equals(exam.getSession().getId(), sessionId) || !Objects.equals(exam.getStudent().getId(), studentId))
            throw new AppException(ErrorCode.EXAM_SESSION_CONFLICT);
        questions.lockPersistedSet(id);
        return exam;
    }
}
