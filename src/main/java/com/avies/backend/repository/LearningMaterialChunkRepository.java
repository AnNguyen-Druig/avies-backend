package com.avies.backend.repository;

import com.avies.backend.entity.LearningMaterialChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface LearningMaterialChunkRepository extends JpaRepository<LearningMaterialChunk, Long> {

    // Query Cosine Similarity dựa trên toán tử <=> của pgvector, chỉ lấy các cột cần thiết cho Context RAG
    @Query(value = """
            SELECT c.id AS id, c.content AS content, m.title AS materialTitle
            FROM aives.learning_material_chunks c
            JOIN aives.learning_materials m ON c.learning_material_id = m.id
            WHERE m.subject_id = :subjectId
            ORDER BY c.embedding <=> CAST(:queryEmbedding AS public.vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<LearningMaterialChunkProjection> searchSimilarChunks(
            @Param("subjectId") Long subjectId,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK
    );

    @Query(value = """
            SELECT c.id AS id, c.content AS content, m.title AS materialTitle
            FROM aives.learning_material_chunks c
            JOIN aives.learning_materials m ON c.learning_material_id = m.id
            WHERE m.subject_id = :subjectId AND m.id IN (:materialIds)
            ORDER BY c.embedding <=> CAST(:queryEmbedding AS public.vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<LearningMaterialChunkProjection> searchSimilarChunksByMaterialIds(
            @Param("subjectId") Long subjectId,
            @Param("materialIds") List<Long> materialIds,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK
    );
}