package com.avies.backend.service.support;

import com.avies.backend.entity.ExamSession;
import com.avies.backend.entity.User;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Chính sách dùng chung cho CRUD và phân công; mọi lời gọi phải nằm trong transaction của service. */
@Component
@RequiredArgsConstructor
public class ExamAccessPolicy {
    /** Tài khoản hiện hành và khóa chung với API phân công môn. */
    private final UserRepository users;
    /** Môn được giữ tồn tại trong transaction ghi. */
    private final SubjectRepository subjects;
    /** Nguồn kiểm tra phân công hiện hành. */
    private final LecturerSubjectAssignmentRepository assignments;
    /** Parent dùng chung cho mọi thao tác lịch và bài thi con. */
    private final ExamSessionRepository sessions;
    /** Refresh entity đã đọc trước khi lấy khóa để không quyết định trên trạng thái cũ. */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Khóa nội bộ cho Start tương lai: không cấp quyền JWT thay caller và không đổi trạng thái.
     * Caller tin cậy phải giữ transaction ghi và kiểm tra quyền trước khi sử dụng kết quả selection.
     * Không dùng ownedForWrite vì STUDENT có thể là người gọi Start nhưng không phải lecturer owner.
     */
    public ExamSession lockForInternalExamOperation(Long id) {
        ExamSession session = sessions.findForDisplay(id)
                .orElseThrow(() -> new AppException(ErrorCode.EXAM_SESSION_NOT_FOUND));
        Long ownerId = session.getLecturer().getId();
        Long subjectId = session.getSubject().getId();
        users.tryLockForExamSessionWrite(ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.EXAM_SESSION_CONFLICT));
        if (subjects.findAllForAssignment(List.of(subjectId)).isEmpty())
            throw new AppException(ErrorCode.EXAM_SESSION_NOT_FOUND);
        sessions.lockForManagement(id).orElseThrow(() -> new AppException(ErrorCode.EXAM_SESSION_NOT_FOUND));
        entityManager.refresh(session);
        if (!Objects.equals(ownerId, session.getLecturer().getId()) || !Objects.equals(subjectId, session.getSubject().getId()))
            throw new AppException(ErrorCode.EXAM_SESSION_CONFLICT);
        return session;
    }

    /** Đối chiếu danh tính JWT với role trong DB; không tin role hoặc ID do request body gửi lên. */
    public User currentUser(String... allowedRoles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken)
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        User actor = users.findByUsername(authentication.getName())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        requireRole(actor, allowedRoles);
        return actor;
    }

    /** Cùng khóa user FOR UPDATE NOWAIT với CRUD cũ; refresh rồi kiểm tra lại quyền ghi. */
    public User lockWriter() {
        User actor = currentUser("LECTURER");
        users.tryLockForExamSessionWrite(actor.getId())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        entityManager.refresh(actor);
        requireRole(actor, "LECTURER");
        return actor;
    }

    /** Chủ sở hữu hoặc ADMIN đọc lịch sử, không bắt buộc còn phân công môn. */
    public ExamSession readableSession(Long id) {
        User actor = currentUser("LECTURER", "ADMIN");
        ExamSession session = sessions.findForDisplay(id)
                .orElseThrow(() -> new AppException(ErrorCode.EXAM_SESSION_NOT_FOUND));
        requireVisible(session, actor);
        return session;
    }

    /** Khóa lecturer → subject → parent session, cùng giao thức CRUD và thu hồi môn. */
    public ExamSession ownedForWrite(Long id) {
        User actor = lockWriter();
        ExamSession session = sessions.findForDisplay(id)
                .orElseThrow(() -> new AppException(ErrorCode.EXAM_SESSION_NOT_FOUND));
        requireVisible(session, actor);
        Long subjectId = session.getSubject().getId();
        var found = subjects.findAllForAssignment(List.of(subjectId));
        if (found.isEmpty()) throw new AppException(ErrorCode.SUBJECT_NOT_EXISTED);
        if (!assignments.existsByLecturerIdAndSubjectId(actor.getId(), subjectId))
            throw new AppException(ErrorCode.SUBJECT_NOT_ASSIGNED);
        sessions.lockForManagement(id).orElseThrow(() -> new AppException(ErrorCode.EXAM_SESSION_NOT_FOUND));
        entityManager.refresh(session);
        requireVisible(session, actor);
        if (!Objects.equals(subjectId, session.getSubject().getId()))
            throw new AppException(ErrorCode.EXAM_SESSION_CONFLICT);
        return session;
    }

    /** Che phiên người khác bằng cùng 404 như phiên không tồn tại. */
    private void requireVisible(ExamSession session, User actor) {
        if (!"ADMIN".equals(actor.getRole().getCode())
                && !Objects.equals(session.getLecturer().getId(), actor.getId()))
            throw new AppException(ErrorCode.EXAM_SESSION_NOT_FOUND);
    }

    /** Role trong DB và authority JWT phải nhất quán, kể cả sau refresh dưới khóa. */
    private void requireRole(User actor, String... allowedRoles) {
        String role = actor.getRole() == null ? "" : actor.getRole().getCode();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authority = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
        if (!authority || !Arrays.asList(allowedRoles).contains(role))
            throw new AppException(ErrorCode.UNAUTHORIZED);
    }
}
