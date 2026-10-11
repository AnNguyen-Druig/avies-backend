package com.avies.backend.repository;

/**
 * Projection interface cho truy vấn Cosine Similarity trên pgvector.
 * Tránh load column embedding (vector binary) vào JPA entity gây lỗi ép kiểu JDBC PGobject.
 */
public interface LearningMaterialChunkProjection {
    Long getId();
    String getContent();
    String getMaterialTitle();
}
