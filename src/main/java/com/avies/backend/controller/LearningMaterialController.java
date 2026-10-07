package com.avies.backend.controller;

import com.avies.backend.dto.request.MaterialUpdateRequest;
import com.avies.backend.dto.response.ApiResponse;
import com.avies.backend.dto.response.MaterialUploadResponse;
import com.avies.backend.service.LearningMaterialService;
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
@RestController
@RequestMapping("/materials")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class LearningMaterialController {

    LearningMaterialService learningMaterialService;

    /**
     * API Upload tài liệu môn học:
     * - Bóc tách văn bản qua Apache Tika
     * - Băm nhỏ thành Chunks tối ưu cho RAG
     * - Sinh Embeddings 1536 chiều bằng text-embedding-3-small
     * - Lưu vào PostgreSQL pgvector
     */
    @PreAuthorize("hasAnyAuthority('ROLE_LECTURER', 'ROLE_ADMIN')")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MaterialUploadResponse> uploadMaterial(
            @RequestParam("file") MultipartFile file,
            @RequestParam("subjectId") Long subjectId,
            @RequestParam(value = "title", required = false) String title
    ) {
        log.info("Nhận yêu cầu upload tài liệu cho môn học ID={}, file={}", subjectId, file.getOriginalFilename());

        MaterialUploadResponse response = learningMaterialService.uploadAndProcessMaterial(subjectId, title, file);

        return ApiResponse.<MaterialUploadResponse>builder()
                .code(1000)
                .message("Tài liệu đã được tải lên và xử lý vector RAG thành công")
                .result(response)
                .build();
    }

    /**
     * Lấy danh sách tài liệu theo môn học
     */
    @PreAuthorize("hasAnyAuthority('ROLE_LECTURER', 'ROLE_ADMIN')")
    @GetMapping("/subject/{subjectId}")
    public ApiResponse<List<MaterialUploadResponse>> getMaterialsBySubject(
            @PathVariable("subjectId") Long subjectId
    ) {
        List<MaterialUploadResponse> list = learningMaterialService.getMaterialsBySubject(subjectId);
        return ApiResponse.<List<MaterialUploadResponse>>builder()
                .result(list)
                .build();
    }

    /**
     * Xóa tài liệu học tập (chỉ ADMIN):
     * - Xóa file vật lý khỏi ổ đĩa
     * - Xóa toàn bộ chunks & vectors trong DB (CASCADE)
     */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @DeleteMapping("/{materialId}")
    public ApiResponse<Void> deleteMaterial(
            @PathVariable("materialId") Long materialId
    ) {
        log.info("Nhận yêu cầu xóa tài liệu id={}", materialId);
        learningMaterialService.deleteMaterial(materialId);
        return ApiResponse.<Void>builder()
                .code(1000)
                .message("Tài liệu và toàn bộ dữ liệu vector đã được xóa thành công")
                .build();
    }

    /**
     * Cập nhật tiêu đề tài liệu (chỉ ADMIN — metadata only, không tái xử lý chunks).
     * Body: { "title": "Tiêu đề mới" }
     */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @PatchMapping("/{materialId}/title")
    public ApiResponse<MaterialUploadResponse> updateMaterialTitle(
            @PathVariable("materialId") Long materialId,
            @RequestBody @Valid MaterialUpdateRequest request
    ) {
        log.info("Nhận yêu cầu cập nhật tiêu đề tài liệu id={}, tiêu đề mới='{}'", materialId, request.getTitle());
        MaterialUploadResponse response = learningMaterialService.updateMaterialTitle(materialId, request.getTitle());
        return ApiResponse.<MaterialUploadResponse>builder()
                .code(1000)
                .message("Cập nhật tiêu đề tài liệu thành công")
                .result(response)
                .build();
    }
}
