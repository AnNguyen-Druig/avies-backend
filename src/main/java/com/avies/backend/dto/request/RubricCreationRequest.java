package com.avies.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

/**
 * Yêu cầu tạo mới Rubric cho một môn học.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RubricCreationRequest {

    @NotNull(message = "Mã môn học không được để trống")
    Long subjectId;

    String title;

    String description;

    @NotEmpty(message = "Danh sách tiêu chí không được để trống")
    @Valid
    List<RubricCriterionRequest> criteria;
}
