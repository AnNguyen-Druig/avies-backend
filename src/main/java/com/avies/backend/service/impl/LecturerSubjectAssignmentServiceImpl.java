package com.avies.backend.service.impl;

import com.avies.backend.dto.request.AssignmentPageRequest;
import com.avies.backend.dto.request.LecturerSubjectAssignmentRequest;
import com.avies.backend.dto.response.*;
import com.avies.backend.entity.LecturerSubjectAssignment;
import com.avies.backend.entity.Subject;
import com.avies.backend.entity.User;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.LecturerSubjectAssignmentRepository;
import com.avies.backend.repository.SubjectRepository;
import com.avies.backend.repository.UserRepository;
import com.avies.backend.service.LecturerSubjectAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Quản lý phân công bằng bảng hiện có, kiểm tra quyền và khóa đồng thời tại backend.
 */
@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LecturerSubjectAssignmentServiceImpl implements LecturerSubjectAssignmentService {

    private final LecturerSubjectAssignmentRepository assignmentRepository;

    private final UserRepository userRepository;

    private final SubjectRepository subjectRepository;

    /**
     * Bổ sung phân công trong một transaction; gửi lặp không tạo thêm bản ghi.
     */
    @Override
    @Transactional
    public AssignmentChangeResponse assign(LecturerSubjectAssignmentRequest request) {
        return changeAssignments(request, false);
    }

    /**
     * Chỉ xóa liên kết phân công; không tác động câu hỏi, rubric hoặc tài liệu.
     */
    @Override
    @Transactional
    public AssignmentChangeResponse revoke(LecturerSubjectAssignmentRequest request) {
        return changeAssignments(request, true);
    }

    /**
     * Khóa cùng tài khoản đích cho cả thêm và thu hồi, rồi kiểm tra đủ môn trước khi ghi.
     */
    private AssignmentChangeResponse changeAssignments(LecturerSubjectAssignmentRequest request, boolean revoke) {
        requireActor("ADMIN");
        userRepository.lockForSubjectAssignment(request.getLecturerId())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        User lecturer = requireLecturer(request.getLecturerId());
        Set<Long> requestedIds = new TreeSet<>(request.getSubjectIds());
        List<Subject> subjects = subjectRepository.findAllForAssignment(requestedIds);
        if (subjects.size() != requestedIds.size()) {
            throw new AppException(ErrorCode.SUBJECT_NOT_EXISTED);
        }
        List<LecturerSubjectAssignment> existing = assignmentRepository
                .findByLecturerIdAndSubjectIdIn(lecturer.getId(), requestedIds);
        Set<Long> existingIds = existing.stream().map(a -> a.getSubject().getId()).collect(Collectors.toSet());
        List<Long> changedIds = requestedIds.stream()
                .filter(id -> revoke ? existingIds.contains(id) : !existingIds.contains(id)).toList();
        List<Long> unchangedIds = requestedIds.stream()
                .filter(id -> revoke ? !existingIds.contains(id) : existingIds.contains(id)).toList();
        if (revoke) {
            assignmentRepository.deleteAll(existing);
        } else {
            assignmentRepository.saveAll(subjects.stream().filter(s -> !existingIds.contains(s.getId()))
                    .map(s -> LecturerSubjectAssignment.builder().lecturer(lecturer).subject(s).build()).toList());
        }
        assignmentRepository.flush();
        return AssignmentChangeResponse.builder().lecturerId(lecturer.getId())
                .changedSubjectIds(changedIds).unchangedSubjectIds(unchangedIds).build();
    }

    /**
     * Trả danh sách phân công ổn định theo ID, không trả thông tin mật của tài khoản.
     */
    @Override
    public PageResponse<LecturerSubjectAssignmentResponse> getAssignments(Long lecturerId, AssignmentPageRequest request) {
        requireActor("ADMIN");
        if (lecturerId != null) requireLecturer(lecturerId);
        Page<LecturerSubjectAssignmentResponse> page = assignmentRepository.findAssignments(lecturerId,
                        PageRequest.of(request.getPage(), request.getSize(), Sort.by("id")))
                .map(a -> LecturerSubjectAssignmentResponse.builder().id(a.getId())
                        .lecturerId(a.getLecturer().getId()).lecturerUsername(a.getLecturer().getUsername())
                        .lecturerFullName(a.getLecturer().getFullName()).subject(toSubjectResponse(a.getSubject())).build());
        return toPageResponse(page);
    }

    /**
     * Lấy ID giảng viên từ database theo người đăng nhập; lọc ở truy vấn trước khi phân trang.
     */
    @Override
    public PageResponse<SubjectResponse> getMySubjects(AssignmentPageRequest request) {
        User lecturer = requireActor("LECTURER");
        return toPageResponse(assignmentRepository.findByLecturerId(lecturer.getId(),
                        PageRequest.of(request.getPage(), request.getSize(), Sort.by("subject.code").and(Sort.by("id"))))
                .map(a -> toSubjectResponse(a.getSubject())));
    }

    /**
     * Kiểm tra vai trò trong database để token cũ không giữ quyền đã bị thu hồi.
     */
    private User requireActor(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        User actor = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        if (actor.getRole() == null || !role.equals(actor.getRole().getCode())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return actor;
    }

    /**
     * Tài khoản đích phải tồn tại và hiện là giảng viên.
     */
    private User requireLecturer(Long id) {
        User lecturer = userRepository.findById(id).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        if (lecturer.getRole() == null || !"LECTURER".equals(lecturer.getRole().getCode())) {
            throw new AppException(ErrorCode.ASSIGNMENT_NOT_LECTURER);
        }
        return lecturer;
    }

    /**
     * Ánh xạ môn học sang DTO hiện có dành cho dropdown.
     */
    private SubjectResponse toSubjectResponse(Subject subject) {
        return SubjectResponse.builder().id(subject.getId()).code(subject.getCode())
                .name(subject.getName()).description(subject.getDescription()).build();
    }

    /**
     * Giữ cấu trúc phân trang thống nhất với API Question.
     */
    private <T> PageResponse<T> toPageResponse(Page<T> page) {
        return PageResponse.<T>builder().items(page.getContent()).page(page.getNumber()).size(page.getSize())
                .totalElements(page.getTotalElements()).totalPages(page.getTotalPages()).build();
    }
}
