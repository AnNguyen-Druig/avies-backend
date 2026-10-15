package com.avies.backend.controller;

import com.avies.backend.dto.request.SubjectCreationRequest;
import com.avies.backend.dto.request.SubjectUpdateRequest;
import com.avies.backend.dto.response.ApiResponse;
import com.avies.backend.dto.response.SubjectResponse;
import com.avies.backend.service.SubjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "Subjects", description = "Quản lý môn học: tạo, xem, cập nhật, xóa")
@RestController
@RequestMapping("/subjects")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SubjectController {

    SubjectService subjectService;

    @Operation(summary = "Tạo môn học mới", description = "Admin hoặc Giảng viên tạo môn học mới vào hệ thống")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tạo thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Mã môn học đã tồn tại"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Không có quyền")
    })
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER')")
    @PostMapping
    public ApiResponse<SubjectResponse> createSubject(@Valid @RequestBody SubjectCreationRequest request) {
        log.info("Nhan yeu cau tao mon hoc moi: code={}", request.getCode());
        SubjectResponse response = subjectService.createSubject(request);
        return ApiResponse.<SubjectResponse>builder()
                .code(1000)
                .message("Tạo môn học thành công")
                .result(response)
                .build();
    }

    @Operation(summary = "Lấy danh sách tất cả môn học", description = "Trả về toàn bộ danh sách môn học cho dropdown UI")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Danh sách môn học")
    })
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER', 'ROLE_STUDENT')")
    @GetMapping
    public ApiResponse<List<SubjectResponse>> getAllSubjects() {
        List<SubjectResponse> subjects = subjectService.getAllSubjects();
        return ApiResponse.<List<SubjectResponse>>builder()
                .code(1000)
                .result(subjects)
                .build();
    }

    @Operation(summary = "Lấy chi tiết môn học", description = "Tra cứu thông tin môn học theo ID")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Thông tin môn học"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy môn học")
    })
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER', 'ROLE_STUDENT')")
    @GetMapping("/{id}")
    public ApiResponse<SubjectResponse> getSubjectById(
            @Parameter(description = "ID môn học") @PathVariable("id") Long id) {
        SubjectResponse response = subjectService.getSubjectById(id);
        return ApiResponse.<SubjectResponse>builder()
                .code(1000)
                .result(response)
                .build();
    }

    @Operation(summary = "Cập nhật môn học", description = "Admin hoặc Giảng viên cập nhật thông tin môn học")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cập nhật thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy môn học")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER')")
    public ApiResponse<SubjectResponse> updateSubject(
            @Parameter(description = "ID môn học") @PathVariable Long id,
            @Valid @RequestBody SubjectUpdateRequest request) {
        return ApiResponse.<SubjectResponse>builder()
                .code(1000)
                .result(subjectService.updateSubject(id, request))
                .build();
    }

    @Operation(summary = "Xóa môn học", description = "Xóa môn học khỏi hệ thống (chỉ Admin hoặc Giảng viên)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Xóa thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy môn học")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER')")
    public ApiResponse<String> deleteSubject(
            @Parameter(description = "ID môn học") @PathVariable Long id) {
        subjectService.deleteSubject(id);
        return ApiResponse.<String>builder()
                .result("Xóa môn học thành công")
                .build();
    }
}