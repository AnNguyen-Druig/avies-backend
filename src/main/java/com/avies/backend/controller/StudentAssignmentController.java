package com.avies.backend.controller;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.*;
import com.avies.backend.service.StudentExamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** API subresource sinh viên của phiên; quyền owner và khóa được giải quyết tại service. */
@RestController
@RequestMapping("/exam-sessions/{id}/students")
@RequiredArgsConstructor
@Tag(name = "Student assignment")
public class StudentAssignmentController {

    private final StudentExamService service;

    /** Batch nguyên tử trả 200 cho cả lần mới và lần gửi lại; không đổi lịch đã có. */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_LECTURER')")
    @Operation(summary = "Chủ sở hữu gán sinh viên và chia slot", description = "studentIds gồm 1..100 JSON integer dương; gộp trùng theo thứ tự đầu tiên. created/unchanged phân biệt kết quả. Cần phân công môn, phiên UPCOMING có lịch tương lai hợp lệ.")
    public ApiResponse<StudentAssignmentResponse> assign(@PathVariable @Positive Long id, @Valid @RequestBody StudentAssignmentRequest request) {
        return ApiResponse.<StudentAssignmentResponse>builder().result(service.assign(id, request)).build();
    }

    /** Đọc lịch sử sở hữu sau thu hồi môn; ADMIN chỉ đọc, STUDENT không được xem danh sách lớp. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_LECTURER', 'ROLE_ADMIN')")
    @Operation(summary = "Xem sinh viên và slot đã gán", description = "Phân trang theo slotOrder tăng dần, NULL cuối, rồi ID; không trả điểm hoặc dữ liệu thi.")
    public ApiResponse<PageResponse<StudentExamAssignmentResponse>> assigned(@PathVariable @Positive Long id,
            @Valid @ModelAttribute ExamAssignmentPageRequest request) {
        return ApiResponse.<PageResponse<StudentExamAssignmentResponse>>builder().result(service.assigned(id, request)).build();
    }
}
