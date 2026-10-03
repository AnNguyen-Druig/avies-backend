package com.avies.backend.repository;

import com.avies.backend.entity.LearningMaterialChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface LearningMaterialChunkRepository extends JpaRepository<LearningMaterialChunk, Long> {

    // Query Cosine Similarity dựa trên toán tử <=> của pgvector
    @Query(value = """
            SELECT c.* 
            FROM aives.learning_material_chunks c
            JOIN aives.learning_materials m ON c.learning_material_id = m.id
            WHERE m.subject_id = :subjectId
            ORDER BY c.embedding <=> CAST(:queryEmbedding AS public.vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<LearningMaterialChunk> searchSimilarChunks(
            @Param("subjectId") Long subjectId,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK
    );
}