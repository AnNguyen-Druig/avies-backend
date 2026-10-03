package com.avies.backend;

import com.avies.backend.repository.LearningMaterialChunkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class AviesBackendApplicationTests {

    @Autowired
    private LearningMaterialChunkRepository chunkRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private com.avies.backend.service.DocumentParserService documentParserService;

    @Autowired
    private com.avies.backend.service.TextChunkingService textChunkingService;

    @Autowired
    private com.avies.backend.service.OpenAiEmbeddingService openAiEmbeddingService;

    @Test
    void testChunkVectorOperations() {
        assertNotNull(chunkRepository);
        long count = chunkRepository.count();
        System.out.println("Current chunk count: " + count);
    }

    @Test
    void testDocumentParserAndChunking() {
        String sampleText = """
                Chương 1: Tổng quan về Trí tuệ Nhân tạo (AI).
                Trí tuệ nhân tạo là ngành khoa học máy tính liên quan đến việc xây dựng các máy móc thông minh.
                Hệ thống RAG (Retrieval-Augmented Generation) kết hợp giữa tìm kiếm thông tin và mô hình sinh ngôn ngữ lớn.
                
                Chương 2: Kiến trúc pgvector và Vector Database.
                pgvector là một extension mã nguồn mở dành cho PostgreSQL cho phép lưu trữ và tìm kiếm vector tương đồng.
                Các thuật toán index phổ biến bao gồm HNSW và IVFFlat, giúp tối ưu hóa tốc độ truy vấn Cosine Similarity.
                """;
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file",
                "test_lecture.txt",
                "text/plain",
                sampleText.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        String extracted = documentParserService.extractText(file);
        assertNotNull(extracted);
        org.junit.jupiter.api.Assertions.assertTrue(extracted.contains("Trí tuệ nhân tạo"));

        var chunks = textChunkingService.chunkText(extracted, 100, 20);
        org.junit.jupiter.api.Assertions.assertFalse(chunks.isEmpty());
        System.out.println("Generated chunks: " + chunks.size());

        var embeddings = openAiEmbeddingService.generateEmbeddings(chunks);
        org.junit.jupiter.api.Assertions.assertEquals(chunks.size(), embeddings.size());
        org.junit.jupiter.api.Assertions.assertEquals(1536, embeddings.get(0).length);
        System.out.println("Vector dimension: " + embeddings.get(0).length);
    }
}

