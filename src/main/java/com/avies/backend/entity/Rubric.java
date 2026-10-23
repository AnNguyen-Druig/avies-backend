package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rubrics", schema = "aives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Rubric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    // Quan hệ 1-1 với Subject (subject_id là UNIQUE)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id", nullable = false, unique = true)
    Subject subject;

    @Column(name = "title", length = 255)
    String title;

    @Column(name = "description", columnDefinition = "TEXT")
    String description;

    // Quan hệ 1-N với RubricCriterion, tự động xóa tiêu chí khi xóa Rubric
    @OneToMany(mappedBy = "rubric", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<RubricCriterion> criteria = new ArrayList<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    OffsetDateTime updatedAt = OffsetDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (updatedAt == null) {
            updatedAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}