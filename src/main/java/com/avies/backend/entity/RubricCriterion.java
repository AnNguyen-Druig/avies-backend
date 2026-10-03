package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;

@Entity
@Table(name = "rubric_criteria", schema = "aives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RubricCriterion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    // Quan hệ N-1 với Rubric
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rubric_id", nullable = false)
    Rubric rubric;

    @Column(name = "criterion_text", columnDefinition = "TEXT", nullable = false)
    String criterionText;

    @Column(name = "point_weight", precision = 5, scale = 2, nullable = false)
    BigDecimal pointWeight;

    @Column(name = "is_mandatory", nullable = false)
    @Builder.Default
    Boolean isMandatory = false;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    Integer displayOrder = 1;
}