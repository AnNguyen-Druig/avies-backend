package com.avies.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;

/**
 * Yêu cầu tạo/cập nhật từng tiêu chí đánh giá trong Rubric.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RubricCriterionRequest {

    Long id;

    @NotBlank(message = "Nội dung tiêu chí không được để trống")
    String criterionText;

    @NotNull(message = "Điểm tiêu chí không được để trống")
    @DecimalMin(value = "0.01", message = "Điểm tiêu chí phải lớn hơn 0")
    @DecimalMax(value = "10.00", message = "Điểm tiêu chí không được vượt quá 10")
    BigDecimal pointWeight;

    @Builder.Default
    Boolean isMandatory = false;

    Integer displayOrder;
}
