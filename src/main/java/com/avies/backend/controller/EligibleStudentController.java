package com.avies.backend.controller;

import com.avies.backend.dto.request.EligibleStudentSearchRequest;
import com.avies.backend.dto.response.*;
import com.avies.backend.service.StudentExamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Tra cứu sinh viên tối thiểu phục vụ chọn người thi, không mở API quản trị tài khoản. */
@RestController
@RequestMapping("/students")
@RequiredArgsConstructor
@Tag(name = "Student assignment", description = "Gán sinh viên và chia slot cơ bản")
public class EligibleStudentController {

    private final StudentExamService service;

    /** Query theo username/họ tên; page từ 0, size 1..100, chỉ role STUDENT hiện hành. */
    @GetMapping("/eligible")
    @PreAuthorize("hasAuthority('ROLE_LECTURER')")
    @Operation(summary = "Giảng viên tìm sinh viên có thể gán", description = "query literal tối đa 100 ký tự; không có enrollment filter. Trả id, username, fullName.")
    public ApiResponse<PageResponse<EligibleStudentResponse>> eligible(@Valid @ModelAttribute EligibleStudentSearchRequest request) {
        return ApiResponse.<PageResponse<EligibleStudentResponse>>builder().result(service.eligible(request)).build();
    }
}
