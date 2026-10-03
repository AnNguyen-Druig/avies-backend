package com.avies.backend.controller;

import com.avies.backend.dto.request.SubjectCreationRequest;
import com.avies.backend.dto.response.ApiResponse;
import com.avies.backend.dto.response.SubjectResponse;
import com.avies.backend.service.SubjectService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/subjects")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SubjectController {

    SubjectService subjectService;

    /**
     * Tạo môn học mới (dành cho Admin / Giảng viên)
     */
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER')")
    @PostMapping
    public ApiResponse<SubjectResponse> createSubject(@Valid @RequestBody SubjectCreationRequest request) {
        log.info("Nhận yêu cầu tạo môn học mới: code={}", request.getCode());
        SubjectResponse response = subjectService.createSubject(request);

        return ApiResponse.<SubjectResponse>builder()
                .code(1000)
                .message("Tạo môn học thành công")
                .result(response)
                .build();
    }

    /**
     * Lấy danh sách môn học để hiển thị dropdown cho Giảng viên chọn khi upload tài liệu
     */
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER', 'ROLE_STUDENT')")
    @GetMapping
    public ApiResponse<List<SubjectResponse>> getAllSubjects() {
        List<SubjectResponse> subjects = subjectService.getAllSubjects();

        return ApiResponse.<List<SubjectResponse>>builder()
                .code(1000)
                .result(subjects)
                .build();
    }

    /**
     * Lấy chi tiết môn học theo ID
     */
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER', 'ROLE_STUDENT')")
    @GetMapping("/{id}")
    public ApiResponse<SubjectResponse> getSubjectById(@PathVariable("id") Long id) {
        SubjectResponse response = subjectService.getSubjectById(id);

        return ApiResponse.<SubjectResponse>builder()
                .code(1000)
                .result(response)
                .build();
    }
}
