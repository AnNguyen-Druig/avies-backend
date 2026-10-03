package com.avies.backend.service;

import com.avies.backend.dto.response.MaterialUploadResponse;
import com.avies.backend.entity.LearningMaterial;
import com.avies.backend.entity.Subject;
import com.avies.backend.entity.User;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.LearningMaterialChunkRepository;
import com.avies.backend.repository.LearningMaterialRepository;
import com.avies.backend.repository.SubjectRepository;
import com.avies.backend.repository.UserRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class LearningMaterialService {

    final SubjectRepository subjectRepository;
    final UserRepository userRepository;
    final LearningMaterialRepository learningMaterialRepository;
    final LearningMaterialChunkRepository chunkRepository;
    final DocumentParserService documentParserService;
    final TextChunkingService textChunkingService;
    final OpenAiEmbeddingService openAiEmbeddingService;
    final JdbcTemplate jdbcTemplate;

    @Value("${storage.location:uploads/materials}")
    String storageLocation;

    static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "docx", "doc", "pptx", "ppt", "txt", "md"
    );

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
    public MaterialUploadResponse uploadAndProcessMaterial(Long subjectId, String title, MultipartFile file) {
        // 1. Lấy thông tin user đang đăng nhập
        var context = SecurityContextHolder.getContext();
        String username = context.getAuthentication().getName();
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        // 2. Kiểm tra môn học
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new AppException(ErrorCode.SUBJECT_NOT_EXISTED));

        // 3. Kiểm tra file upload
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.FILE_EMPTY);
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "unnamed_document";
        }

        String extension = getFileExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new AppException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        // 4. Lưu file vật lý
        String storedPath = savePhysicalFile(file, originalFilename);

        // 5. Khởi tạo LearningMaterial
        String documentTitle = (title != null && !title.isBlank()) ? title.trim() : originalFilename;
        LearningMaterial material = LearningMaterial.builder()
                .subject(subject)
                .uploadedBy(currentUser)
                .title(documentTitle)
                .fileName(originalFilename)
                .storagePath(storedPath)
                .mimeType(file.getContentType())
                .processingStatus("PROCESSING")
                .build();

        material = learningMaterialRepository.save(material);
        log.info("Khởi tạo LearningMaterial id={} cho subjectId={}, user={}", material.getId(), subjectId, username);

        try {
            // 6. Apache Tika bóc tách text
            String extractedText = documentParserService.extractText(file);

            // 7. Băm nhỏ thành Chunks
            List<String> chunks = textChunkingService.chunkText(extractedText);
            if (chunks.isEmpty()) {
                throw new AppException(ErrorCode.FILE_PARSING_FAILED);
            }

            // 8. Đổi ra Embeddings qua OpenAI text-embedding-3-small
            List<float[]> embeddings = openAiEmbeddingService.generateEmbeddings(chunks);

            // 9. Lưu vào PostgreSQL pgvector
            saveChunksWithVectors(material.getId(), chunks, embeddings);

            // 10. Hoàn tất xử lý
            material.setProcessingStatus("READY");
            material = learningMaterialRepository.save(material);
            log.info("Xử lý RAG thành công cho Material id={}, tổng cộng {} chunks", material.getId(), chunks.size());

            return MaterialUploadResponse.builder()
                    .id(material.getId())
                    .subjectId(subject.getId())
                    .subjectCode(subject.getCode())
                    .subjectName(subject.getName())
                    .title(material.getTitle())
                    .fileName(material.getFileName())
                    .storagePath(material.getStoragePath())
                    .mimeType(material.getMimeType())
                    .totalChunks(chunks.size())
                    .processingStatus("READY")
                    .uploadedBy(currentUser.getFullName() != null ? currentUser.getFullName() : currentUser.getUsername())
                    .createdAt(material.getCreatedAt())
                    .build();

        } catch (Exception e) {
            log.error("Xảy ra lỗi trong quá trình xử lý tài liệu id={}: {}", material.getId(), e.getMessage(), e);
            material.setProcessingStatus("FAILED");
            learningMaterialRepository.save(material);
            if (e instanceof AppException appException) {
                throw appException;
            }
            throw new AppException(ErrorCode.FILE_PARSING_FAILED);
        }
    }

    /**
     * Batch insert chunks và vector embeddings vào bảng aives.learning_material_chunks
     */
    private void saveChunksWithVectors(Long materialId, List<String> chunks, List<float[]> embeddings) {
        String sql = """
            INSERT INTO aives.learning_material_chunks (learning_material_id, chunk_index, content, embedding)
            VALUES (?, ?, ?, CAST(? AS public.vector))
        """;

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setLong(1, materialId);
                ps.setInt(2, i);
                ps.setString(3, chunks.get(i));
                ps.setString(4, Arrays.toString(embeddings.get(i)));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    private String savePhysicalFile(MultipartFile file, String originalFilename) {
        try {
            Path targetDir = Paths.get(storageLocation).toAbsolutePath().normalize();
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            String uniqueName = UUID.randomUUID() + "_" + originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
            Path targetPath = targetDir.resolve(uniqueName);

            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            return targetPath.toString();
        } catch (IOException e) {
            log.error("Lỗi khi lưu file vật lý lên ổ đĩa: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.FILE_PARSING_FAILED);
        }
    }

    private String getFileExtension(String filename) {
        int lastIndex = filename.lastIndexOf('.');
        if (lastIndex == -1) {
            return "";
        }
        return filename.substring(lastIndex + 1);
    }

    @Transactional(readOnly = true)
    public List<MaterialUploadResponse> getMaterialsBySubject(Long subjectId) {
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new AppException(ErrorCode.SUBJECT_NOT_EXISTED));

        return learningMaterialRepository.findBySubjectId(subjectId).stream()
                .map(m -> MaterialUploadResponse.builder()
                        .id(m.getId())
                        .subjectId(subject.getId())
                        .subjectCode(subject.getCode())
                        .subjectName(subject.getName())
                        .title(m.getTitle())
                        .fileName(m.getFileName())
                        .storagePath(m.getStoragePath())
                        .mimeType(m.getMimeType())
                        .processingStatus(m.getProcessingStatus())
                        .uploadedBy(m.getUploadedBy() != null ? m.getUploadedBy().getFullName() : null)
                        .createdAt(m.getCreatedAt())
                        .build())
                .toList();
    }
}
