package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "learning_material_chunks", schema = "aives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class LearningMaterialChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "learning_material_id", nullable = false)
    LearningMaterial learningMaterial;

    @Column(name = "chunk_index", nullable = false)
    Integer chunkIndex;

    @Column(columnDefinition = "TEXT", nullable = false)
    String content;

    // Mapping kiểu vector(1536) của pgvector trong PostgreSQL
    @JdbcTypeCode(SqlTypes.OTHER)
    @Column(name = "embedding", columnDefinition = "vector(1536)")
    float[] embedding;

    @Column(name = "created_at", insertable = false, updatable = false)
    OffsetDateTime createdAt;
}