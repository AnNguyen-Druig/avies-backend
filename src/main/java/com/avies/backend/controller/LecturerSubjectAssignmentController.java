package com.avies.backend.controller;

import com.avies.backend.dto.request.AssignmentPageRequest;
import com.avies.backend.dto.request.LecturerSubjectAssignmentRequest;
import com.avies.backend.dto.response.*;
import com.avies.backend.service.LecturerSubjectAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Bốn API phân công môn; controller chỉ gọi service và không truy cập repository.
 */
@RestController
@RequestMapping("/lecturer-subject-assignments")
@RequiredArgsConstructor
@Tag(name = "Lecturer subject assignments", description = "Phân công và thu hồi môn cho giảng viên")
public class LecturerSubjectAssignmentController {
    /**
     * Dịch vụ chịu trách nhiệm nghiệp vụ, phân quyền dữ liệu và transaction.
     */
    private final LecturerSubjectAssignmentService service;

    /**
     * Admin bổ sung nhiều môn; phân công đã tồn tại được giữ nguyên và vẫn trả thành công.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Admin bổ sung phân công môn", description = "Không thay thế các môn đã phân công. Tối đa 100 mã môn/yêu cầu.")
    public ApiResponse<AssignmentChangeResponse> assign(@Valid @RequestBody LecturerSubjectAssignmentRequest request) {
        return ApiResponse.<AssignmentChangeResponse>builder().result(service.assign(request))
                .message("Đã xử lý phân công; các môn đã được phân công trước đó được giữ nguyên.").build();
    }

    /**
     * Admin thu hồi các liên kết được chọn và nhận thông báo về ảnh hưởng quyền truy cập.
     */
    @PostMapping("/revocations")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Admin thu hồi phân công môn", description = "Không xóa nội dung. Giảng viên mất quyền truy cập Question theo phân công, kể cả câu hỏi của mình.")
    public ApiResponse<AssignmentChangeResponse> revoke(@Valid @RequestBody LecturerSubjectAssignmentRequest request) {
        return ApiResponse.<AssignmentChangeResponse>builder().result(service.revoke(request))
                .message("Đã xử lý thu hồi. Không xóa câu hỏi, rubric hoặc tài liệu; giảng viên không còn quyền truy cập Question trong môn đã thu hồi, kể cả câu hỏi của mình.")
                .build();
    }

    /**
     * Admin xem toàn bộ phân công có phân trang, hoặc lọc theo một giảng viên.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Admin xem danh sách phân công", description = "lecturerId không bắt buộc; page bắt đầu từ 0, size từ 1 đến 100.")
    public ApiResponse<PageResponse<LecturerSubjectAssignmentResponse>> getAssignments(
            @RequestParam(required = false) @Positive Long lecturerId,
            @Valid @ModelAttribute AssignmentPageRequest page) {
        return ApiResponse.<PageResponse<LecturerSubjectAssignmentResponse>>builder()
                .result(service.getAssignments(lecturerId, page)).build();
    }

    /**
     * Dropdown của giảng viên chỉ nhận môn của chính mình, lấy danh tính từ token đăng nhập.
     */
    @GetMapping("/my-subjects")
    @PreAuthorize("hasAuthority('ROLE_LECTURER')")
    @Operation(summary = "Giảng viên xem môn được phân công", description = "Không nhận lecturerId. Dùng result.items cho dropdown; tải các trang tiếp theo nếu còn dữ liệu.")
    public ApiResponse<PageResponse<SubjectResponse>> getMySubjects(@Valid @ModelAttribute AssignmentPageRequest page) {
        return ApiResponse.<PageResponse<SubjectResponse>>builder().result(service.getMySubjects(page)).build();
    }
}
