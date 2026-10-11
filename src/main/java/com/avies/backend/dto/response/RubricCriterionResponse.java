package com.avies.backend.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;

/**
 * Tiêu chí đánh giá trong rubric chấm điểm câu hỏi vấn đáp.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RubricCriterionResponse {
    Long id;
    String criterionText;
    BigDecimal pointWeight;
    Boolean isMandatory;
    Integer displayOrder;
}
