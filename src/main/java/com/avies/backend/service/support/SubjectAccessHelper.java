package com.avies.backend.service.support;

import com.avies.backend.entity.Subject;
import com.avies.backend.entity.User;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.LecturerSubjectAssignmentRepository;
import com.avies.backend.repository.SubjectRepository;
import com.avies.backend.repository.UserRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Component dùng chung cho các service cần kiểm tra quyền truy cập và phân công môn học.
 */
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SubjectAccessHelper {

    UserRepository userRepository;
    SubjectRepository subjectRepository;
    LecturerSubjectAssignmentRepository assignmentRepository;

    /**
     * Lấy tài khoản hiện hành từ SecurityContext.
     * Bắt buộc người dùng phải xác thực và có vai trò LECTURER hoặc ADMIN.
     */
    public User getCurrentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        User actor = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        if (actor.getRole() == null || (!isAdmin(actor) && !"LECTURER".equalsIgnoreCase(actor.getRole().getCode()))) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return actor;
    }

    /**
     * Kiểm tra người dùng hiện hành có quyền quản trị hệ thống (ADMIN) hay không.
     */
    public boolean isAdmin(User actor) {
        return actor != null && actor.getRole() != null && "ADMIN".equalsIgnoreCase(actor.getRole().getCode());
    }

    /**
     * Bắt buộc giảng viên phải có phân công với môn học (Admin được toàn quyền).
     */
    public void requireAssignment(User actor, Long subjectId) {
        if (!isAdmin(actor) && !assignmentRepository.existsByLecturerIdAndSubjectId(actor.getId(), subjectId)) {
            throw new AppException(ErrorCode.SUBJECT_NOT_ASSIGNED);
        }
    }

    /**
     * Kiểm tra giảng viên có được phân công môn học này không (trả về boolean).
     */
    public boolean isAssigned(User actor, Long subjectId) {
        return isAdmin(actor) || assignmentRepository.existsByLecturerIdAndSubjectId(actor.getId(), subjectId);
    }

    /**
     * Tìm kiếm môn học theo ID hoặc ném lỗi nếu không tồn tại.
     */
    public Subject getSubjectOrThrow(Long subjectId) {
        return subjectRepository.findById(subjectId)
                .orElseThrow(() -> new AppException(ErrorCode.SUBJECT_NOT_EXISTED));
    }

    /**
     * Tiện ích: Vừa kiểm tra phân công môn học vừa trả về thực thể Subject.
     */
    public Subject requireAssignmentAndGetSubject(User actor, Long subjectId) {
        Subject subject = getSubjectOrThrow(subjectId);
        requireAssignment(actor, subjectId);
        return subject;
    }
}
