package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;

@Entity
@Table(name = "questions", schema = "aives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id", nullable = false)
    Subject subject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    User createdBy;

    @Column(columnDefinition = "TEXT", nullable = false)
    String content;

    @Column(name = "bloom_level")
    String bloomLevel; // REMEMBER, UNDERSTAND, APPLY, ANALYZE, EVALUATE, CREATE

    @Column(name = "is_ai_generated")
    Boolean isAiGenerated;

    @Column(name = "is_approved")
    Boolean isApproved;

    @OneToOne(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    Rubric rubric;

    @Column(name = "created_at", insertable = false, updatable = false)
    OffsetDateTime createdAt;
}