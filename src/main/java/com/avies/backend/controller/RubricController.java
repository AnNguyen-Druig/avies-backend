package com.avies.backend.controller;

import com.avies.backend.dto.request.RubricCreationRequest;
import com.avies.backend.dto.request.RubricUpdateRequest;
import com.avies.backend.dto.response.ApiResponse;
import com.avies.backend.dto.response.RubricResponse;
import com.avies.backend.service.RubricService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Rubrics", description = "Quản lý tiêu chí chấm điểm (Rubric) theo môn học dành cho Admin và Giảng viên được phân công")
@RestController
@RequestMapping("/rubrics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER')")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RubricController {

    RubricService rubricService;

    @Operation(summary = "Tạo mới Rubric cho môn học",
            description = "Admin hoặc Giảng viên được phân công môn học tạo bộ Rubric chuẩn (thang điểm 10.00)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Tạo Rubric thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Dữ liệu hoặc tiêu chí không hợp lệ (tổng điểm != 10.00)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Chưa được phân công môn học này"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Môn học đã có Rubric")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<RubricResponse>> createRubric(@Valid @RequestBody RubricCreationRequest request) {
        log.info("Nhan yeu cau tao Rubric cho subjectId={}", request.getSubjectId());
        RubricResponse response = rubricService.createRubric(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<RubricResponse>builder()
                        .code(1000)
                        .message("Tạo Rubric thành công")
                        .result(response)
                        .build());
    }

    @Operation(summary = "Xem chi tiết Rubric theo ID",
            description = "Lấy thông tin Rubric và danh sách tiêu chí theo ID của Rubric")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Chưa được phân công môn học của Rubric"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy Rubric")
    })
    @GetMapping("/{id}")
    public ApiResponse<RubricResponse> getRubricById(
            @Parameter(description = "ID của Rubric", required = true) @PathVariable Long id) {
        RubricResponse response = rubricService.getRubricById(id);
        return ApiResponse.<RubricResponse>builder()
                .code(1000)
                .result(response)
                .build();
    }

    @Operation(summary = "Xem Rubric của môn học",
            description = "Lấy thông tin Rubric và tiêu chí theo mã môn học (subjectId)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Chưa được phân công môn học này"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Môn học chưa có Rubric")
    })
    @GetMapping("/subject/{subjectId}")
    public ApiResponse<RubricResponse> getRubricBySubjectId(
            @Parameter(description = "ID của môn học", required = true) @PathVariable Long subjectId) {
        RubricResponse response = rubricService.getRubricBySubjectId(subjectId);
        return ApiResponse.<RubricResponse>builder()
                .code(1000)
                .result(response)
                .build();
    }

    @Operation(summary = "Cập nhật Rubric và tiêu chí",
            description = "Cập nhật tiêu đề, mô tả và làm mới danh sách tiêu chí (tổng điểm phải bằng 10.00)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cập nhật thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Dữ liệu hoặc tiêu chí không hợp lệ"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Chưa được phân công môn học này"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy Rubric")
    })
    @PutMapping("/{id}")
    public ApiResponse<RubricResponse> updateRubric(
            @Parameter(description = "ID của Rubric", required = true) @PathVariable Long id,
            @Valid @RequestBody RubricUpdateRequest request) {
        log.info("Nhan yeu cau cap nhat Rubric id={}", id);
        RubricResponse response = rubricService.updateRubric(id, request);
        return ApiResponse.<RubricResponse>builder()
                .code(1000)
                .message("Cập nhật Rubric thành công")
                .result(response)
                .build();
    }

    @Operation(summary = "Xóa Rubric",
            description = "Xóa Rubric và toàn bộ tiêu chí của môn học")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Xóa thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Chưa được phân công môn học này"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy Rubric")
    })
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteRubric(
            @Parameter(description = "ID của Rubric", required = true) @PathVariable Long id) {
        log.info("Nhan yeu cau xoa Rubric id={}", id);
        rubricService.deleteRubric(id);
        return ApiResponse.<Void>builder()
                .code(1000)
                .message("Xóa Rubric thành công")
                .build();
    }
}
