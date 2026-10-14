package com.avies.backend.service;

import com.avies.backend.dto.request.AssignmentPageRequest;
import com.avies.backend.dto.request.LecturerSubjectAssignmentRequest;
import com.avies.backend.dto.response.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Hợp đồng phân công môn; mọi thay đổi chỉ dành cho admin và thực hiện nguyên tử.
 */
public interface LecturerSubjectAssignmentService {
    /**
     * Bổ sung các môn được chọn, không thay thế danh sách phân công hiện có.
     */
    AssignmentChangeResponse assign(@NotNull @Valid LecturerSubjectAssignmentRequest request);

    /**
     * Thu hồi các môn được chọn, không xóa nội dung giảng viên đã tạo.
     */
    AssignmentChangeResponse revoke(@NotNull @Valid LecturerSubjectAssignmentRequest request);

    /**
     * Admin xem tất cả phân công; lecturerId để trống nghĩa là không lọc giảng viên.
     */
    PageResponse<LecturerSubjectAssignmentResponse> getAssignments(
            @Positive Long lecturerId, @NotNull @Valid AssignmentPageRequest page);

    /**
     * Giảng viên xem các môn của chính tài khoản đăng nhập, không nhận ID từ client.
     */
    PageResponse<SubjectResponse> getMySubjects(@NotNull @Valid AssignmentPageRequest page);
}
