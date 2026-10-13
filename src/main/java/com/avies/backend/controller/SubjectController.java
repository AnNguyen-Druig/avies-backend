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
@Tag(name = "Subjects", description = "Quan ly mon hoc: tao, xem, cap nhat, xoa")
@RestController
@RequestMapping("/subjects")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SubjectController {

    SubjectService subjectService;

    @Operation(summary = "Tao mon hoc moi", description = "Admin hoac Giang vien tao mon hoc moi vao he thong")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tao thanh cong"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Ma mon hoc da ton tai"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Khong co quyen")
    })
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER')")
    @PostMapping
    public ApiResponse<SubjectResponse> createSubject(@Valid @RequestBody SubjectCreationRequest request) {
        log.info("Nhan yeu cau tao mon hoc moi: code={}", request.getCode());
        SubjectResponse response = subjectService.createSubject(request);
        return ApiResponse.<SubjectResponse>builder()
                .code(1000)
                .message("Tao mon hoc thanh cong")
                .result(response)
                .build();
    }

    @Operation(summary = "Lay danh sach tat ca mon hoc", description = "Tra ve toan bo danh sach mon hoc cho dropdown UI")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Danh sach mon hoc")
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

    @Operation(summary = "Lay chi tiet mon hoc", description = "Tra cuu thong tin mon hoc theo ID")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Thong tin mon hoc"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Khong tim thay mon hoc")
    })
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER', 'ROLE_STUDENT')")
    @GetMapping("/{id}")
    public ApiResponse<SubjectResponse> getSubjectById(
            @Parameter(description = "ID mon hoc") @PathVariable("id") Long id) {
        SubjectResponse response = subjectService.getSubjectById(id);
        return ApiResponse.<SubjectResponse>builder()
                .code(1000)
                .result(response)
                .build();
    }

    @Operation(summary = "Cap nhat mon hoc", description = "Admin hoac Giang vien cap nhat thong tin mon hoc")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cap nhat thanh cong"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Khong tim thay mon hoc")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER')")
    public ApiResponse<SubjectResponse> updateSubject(
            @Parameter(description = "ID mon hoc") @PathVariable Long id,
            @Valid @RequestBody SubjectUpdateRequest request) {
        return ApiResponse.<SubjectResponse>builder()
                .code(1000)
                .result(subjectService.updateSubject(id, request))
                .build();
    }

    @Operation(summary = "Xoa mon hoc", description = "Xoa mon hoc khoi he thong (chi Admin hoac Giang vien)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Xoa thanh cong"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Khong tim thay mon hoc")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER')")
    public ApiResponse<String> deleteSubject(
            @Parameter(description = "ID mon hoc") @PathVariable Long id) {
        subjectService.deleteSubject(id);
        return ApiResponse.<String>builder()
                .result("Xoa mon hoc thanh cong")
                .build();
    }
}