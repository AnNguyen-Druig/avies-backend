package com.avies.backend.service.support;

import com.avies.backend.entity.StudentExam;
import com.avies.backend.exception.*;
import com.avies.backend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Cùng thứ tự khóa owner → môn → phiên → student → lượt → bộ câu với Feature 2. */
@Component
@RequiredArgsConstructor
public class OralExamAccessPolicy {
    /** Chính sách JWT/role và khóa parent đã có. */ private final ExamAccessPolicy access;
    /** Lượt thi dùng chung, không tạo bản sao. */ private final StudentExamRepository exams;
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
        var session=exam.getSession(); long ownerId=session.getLecturer().getId(), subjectId=session.getSubject().getId();
        if (exams.lockOralHierarchy(id,sessionId,ownerId,subjectId,studentId).isEmpty()) throw new AppException(ErrorCode.EXAM_SESSION_CONFLICT);
        entityManager.refresh(session);
        entityManager.refresh(exam);
        if (!Objects.equals(exam.getSession().getId(), sessionId) || !Objects.equals(exam.getStudent().getId(), studentId)
                || !Objects.equals(session.getLecturer().getId(),ownerId) || !Objects.equals(session.getSubject().getId(),subjectId))
            throw new AppException(ErrorCode.EXAM_SESSION_CONFLICT);
        return exam;
    }
}
