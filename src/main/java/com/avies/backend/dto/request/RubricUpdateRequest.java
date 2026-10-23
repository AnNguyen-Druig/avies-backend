package com.avies.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

/**
 * Yêu cầu cập nhật thông tin Rubric và danh sách tiêu chí.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RubricUpdateRequest {

    String title;

    String description;

    @Valid
    List<RubricCriterionRequest> criteria;
}
