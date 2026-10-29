package com.avies.backend.controller;

import com.avies.backend.dto.response.*;
import com.avies.backend.service.OralExamRuntimeService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Chỉ trả khả năng pipeline, không chứa cấu hình nhà cung cấp hoặc dữ liệu đề. */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('ROLE_STUDENT')")
public class OralExamRuntimeController {
    /** Controller chỉ gọi service. */ private final OralExamRuntimeService service;

    /** Phòng thi đọc khả năng trước khi cho Student xác nhận bắt đầu. */
    @GetMapping("/oral-examination/runtime")
    @Operation(summary = "Xem khả năng pipeline thi vấn đáp")
    public ApiResponse<OralExamRuntimeResponse> get() {
        return ApiResponse.<OralExamRuntimeResponse>builder().result(service.get()).build();
    }
}
