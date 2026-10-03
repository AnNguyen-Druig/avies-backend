package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;

@Entity
@Table(name = "learning_materials", schema = "aives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class LearningMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id", nullable = false)
    Subject subject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    User uploadedBy;

    String title;

    @Column(name = "file_name")
    String fileName;

    @Column(name = "storage_path")
    String storagePath;

    @Column(name = "mime_type")
    String mimeType;

    @Column(name = "processing_status")
    String processingStatus; // PENDING, PROCESSING, READY, FAILED

    @Column(name = "created_at", insertable = false, updatable = false)
    OffsetDateTime createdAt;
}