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
@Tag(name = "Learning Materials", description = "Quan ly tai lieu hoc tap: upload, xem, xoa, cap nhat tieu de (tich hop RAG vector)")
@RestController
@RequestMapping("/materials")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class LearningMaterialController {

    LearningMaterialService learningMaterialService;

    @Operation(summary = "Upload tai lieu hoc tap",
            description = "Upload file tai lieu (PDF, DOCX,...). He thong tu dong: boc tach van ban, chia chunks, sinh embeddings 1536 chieu, luu pgvector.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Upload va xu ly vector thanh cong"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "File khong hop le hoac thieu thong tin"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Chi LECTURER hoac ADMIN moi duoc upload")
    })
    @PreAuthorize("hasAnyAuthority('ROLE_LECTURER', 'ROLE_ADMIN')")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MaterialUploadResponse> uploadMaterial(
            @Parameter(description = "File tai lieu (PDF, DOCX, TXT, ...)") @RequestParam("file") MultipartFile file,
            @Parameter(description = "ID cua mon hoc") @RequestParam("subjectId") Long subjectId,
            @Parameter(description = "Tieu de tai lieu (neu bo trong se lay ten file)") @RequestParam(value = "title", required = false) String title
    ) {
        log.info("Nhan yeu cau upload tai lieu cho mon hoc ID={}, file={}", subjectId, file.getOriginalFilename());
        MaterialUploadResponse response = learningMaterialService.uploadAndProcessMaterial(subjectId, title, file);
        return ApiResponse.<MaterialUploadResponse>builder()
                .code(1000)
                .message("Tai lieu da duoc tai len va xu ly vector RAG thanh cong")
                .result(response)
                .build();
    }

    @Operation(summary = "Lay danh sach tai lieu theo mon hoc", description = "Tra ve tat ca tai lieu da upload cho mot mon hoc")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Danh sach tai lieu"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Khong co quyen")
    })
    @PreAuthorize("hasAnyAuthority('ROLE_LECTURER', 'ROLE_ADMIN')")
    @GetMapping("/subject/{subjectId}")
    public ApiResponse<List<MaterialUploadResponse>> getMaterialsBySubject(
            @Parameter(description = "ID cua mon hoc") @PathVariable("subjectId") Long subjectId
    ) {
        List<MaterialUploadResponse> list = learningMaterialService.getMaterialsBySubject(subjectId);
        return ApiResponse.<List<MaterialUploadResponse>>builder()
                .result(list)
                .build();
    }

    @Operation(summary = "Xoa tai lieu", description = "Chi ADMIN. Xoa file vat ly va toan bo chunks/vectors (CASCADE).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Xoa thanh cong"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Khong tim thay tai lieu"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Khong co quyen")
    })
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @DeleteMapping("/{materialId}")
    public ApiResponse<Void> deleteMaterial(
            @Parameter(description = "ID tai lieu can xoa") @PathVariable("materialId") Long materialId
    ) {
        log.info("Nhan yeu cau xoa tai lieu id={}", materialId);
        learningMaterialService.deleteMaterial(materialId);
        return ApiResponse.<Void>builder()
                .code(1000)
                .message("Tai lieu va toan bo du lieu vector da duoc xoa thanh cong")
                .build();
    }

    @Operation(summary = "Cap nhat tieu de tai lieu", description = "Chi cap nhat metadata (tieu de), khong tai xu ly chunks/embeddings. Chi ADMIN.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cap nhat thanh cong"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Khong tim thay tai lieu")
    })
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @PatchMapping("/{materialId}/title")
    public ApiResponse<MaterialUploadResponse> updateMaterialTitle(
            @Parameter(description = "ID tai lieu") @PathVariable("materialId") Long materialId,
            @RequestBody @Valid MaterialUpdateRequest request
    ) {
        log.info("Nhan yeu cau cap nhat tieu de tai lieu id={}", materialId);
        MaterialUploadResponse response = learningMaterialService.updateMaterialTitle(materialId, request.getTitle());
        return ApiResponse.<MaterialUploadResponse>builder()
                .code(1000)
                .message("Cap nhat tieu de tai lieu thanh cong")
                .result(response)
                .build();
    }
}