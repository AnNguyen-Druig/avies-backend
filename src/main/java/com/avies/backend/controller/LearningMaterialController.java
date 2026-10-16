package com.avies.backend.controller;

import com.avies.backend.dto.request.MaterialUpdateRequest;
import com.avies.backend.dto.response.ApiResponse;
import com.avies.backend.dto.response.MaterialUploadResponse;
import com.avies.backend.service.LearningMaterialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@Tag(name = "Learning Materials", description = "Quản lý tài liệu học tập: upload, xem, xóa, cập nhật tiêu đề (tích hợp RAG vector)")
@RestController
@RequestMapping("/materials")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class LearningMaterialController {

    LearningMaterialService learningMaterialService;

    @Operation(summary = "Upload tài liệu học tập",
            description = "Upload file tài liệu (PDF, DOCX,...). LECTURER phải được phân công môn học; ADMIN không cần phân công. Hệ thống tự động: bóc tách văn bản, chia chunks, sinh embeddings 1536 chiều, lưu pgvector.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Upload và xử lý vector thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "File không hợp lệ hoặc thiếu thông tin"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Không có quyền hoặc LECTURER chưa được phân công môn (mã lỗi 1101)")
    })
    @PreAuthorize("hasAnyAuthority('ROLE_LECTURER', 'ROLE_ADMIN')")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MaterialUploadResponse> uploadMaterial(
            @Parameter(description = "File tài liệu (PDF, DOCX, TXT, ...)") @RequestParam("file") MultipartFile file,
            @Parameter(description = "ID của môn học") @RequestParam("subjectId") Long subjectId,
            @Parameter(description = "Tiêu đề tài liệu (nếu để trống sẽ lấy tên file)") @RequestParam(value = "title", required = false) String title
    ) {
        log.info("Nhan yeu cau upload tai lieu cho mon hoc ID={}, file={}", subjectId, file.getOriginalFilename());
        MaterialUploadResponse response = learningMaterialService.uploadAndProcessMaterial(subjectId, title, file);
        return ApiResponse.<MaterialUploadResponse>builder()
                .code(1000)
                .message("Tài liệu đã được tải lên và xử lý vector RAG thành công")
                .result(response)
                .build();
    }

    @Operation(summary = "Lấy danh sách tài liệu theo môn học", description = "Trả về tài liệu trong môn được phân công cho LECTURER; ADMIN xem được mọi môn. Thu hồi phân công sẽ chặn cả tài liệu do chính giảng viên upload.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Danh sách tài liệu"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Không có quyền hoặc LECTURER chưa được phân công môn (mã lỗi 1101)")
    })
    @PreAuthorize("hasAnyAuthority('ROLE_LECTURER', 'ROLE_ADMIN')")
    @GetMapping("/subject/{subjectId}")
    public ApiResponse<List<MaterialUploadResponse>> getMaterialsBySubject(
            @Parameter(description = "ID của môn học") @PathVariable("subjectId") Long subjectId
    ) {
        List<MaterialUploadResponse> list = learningMaterialService.getMaterialsBySubject(subjectId);
        return ApiResponse.<List<MaterialUploadResponse>>builder()
                .result(list)
                .build();
    }

    @Operation(summary = "Xóa tài liệu", description = "Chỉ ADMIN. Xóa file vật lý và toàn bộ chunks/vectors (CASCADE).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Xóa thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy tài liệu"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Không có quyền")
    })
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @DeleteMapping("/{materialId}")
    public ApiResponse<Void> deleteMaterial(
            @Parameter(description = "ID tài liệu cần xóa") @PathVariable("materialId") Long materialId
    ) {
        log.info("Nhan yeu cau xoa tai lieu id={}", materialId);
        learningMaterialService.deleteMaterial(materialId);
        return ApiResponse.<Void>builder()
                .code(1000)
                .message("Tài liệu và toàn bộ dữ liệu vector đã được xóa thành công")
                .build();
    }

    @Operation(summary = "Cập nhật tiêu đề tài liệu", description = "Chỉ cập nhật metadata (tiêu đề), không tái xử lý chunks/embeddings. Chỉ ADMIN.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cập nhật thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy tài liệu")
    })
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @PatchMapping("/{materialId}/title")
    public ApiResponse<MaterialUploadResponse> updateMaterialTitle(
            @Parameter(description = "ID tài liệu") @PathVariable("materialId") Long materialId,
            @RequestBody @Valid MaterialUpdateRequest request
    ) {
        log.info("Nhan yeu cau cap nhat tieu de tai lieu id={}", materialId);
        MaterialUploadResponse response = learningMaterialService.updateMaterialTitle(materialId, request.getTitle());
        return ApiResponse.<MaterialUploadResponse>builder()
                .code(1000)
                .message("Cập nhật tiêu đề tài liệu thành công")
                .result(response)
                .build();
    }
}
