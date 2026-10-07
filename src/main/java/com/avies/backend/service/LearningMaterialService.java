package com.avies.backend.service;

import com.avies.backend.dto.response.MaterialUploadResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

public interface LearningMaterialService {

    /**
     * Quy trình Upload tài liệu học tập & xử lý RAG:
     * 1. Kiểm tra giảng viên / user đăng nhập và quyền truy cập
     * 2. Xác thực môn học (Subject) và định dạng file
     * 3. Lưu file vật lý vào thư mục lưu trữ
     * 4. Tạo bản ghi LearningMaterial với trạng thái PROCESSING
     * 5. Dùng Apache Tika bóc tách text
     * 6. Băm nhỏ văn bản thành danh sách Chunks có ngữ cảnh (TextChunkingService)
     * 7. Gọi OpenAI sinh vector embeddings (text-embedding-3-small, 1536 dims)
     * 8. Lưu các Chunks kèm vector vào PostgreSQL pgvector
     * 9. Cập nhật trạng thái READY và trả về kết quả
     */
    @Transactional
    MaterialUploadResponse uploadAndProcessMaterial(Long subjectId, String title, MultipartFile file);

    @Transactional(readOnly = true)
    List<MaterialUploadResponse> getMaterialsBySubject(Long subjectId);

    /**
     * Xóa tài liệu: xóa file vật lý trên ổ đĩa + chunks trong DB + bản ghi LearningMaterial.
     * Chỉ người upload hoặc Admin mới được xóa.
     */
    @Transactional
    void deleteMaterial(Long materialId);

    /**
     * Cập nhật tiêu đề tài liệu (metadata only — không tái xử lý chunks).
     */
    @Transactional
    MaterialUploadResponse updateMaterialTitle(Long materialId, String newTitle);
}
