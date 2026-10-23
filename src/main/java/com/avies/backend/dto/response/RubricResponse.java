package com.avies.backend.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Chi tiết rubric chấm điểm gắn với câu hỏi vấn đáp.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RubricResponse {
    Long id;
    Long subjectId;
    String subjectCode;
    String subjectName;
    String title;
    String description;
    List<RubricCriterionResponse> criteria;
    OffsetDateTime createdAt;
    OffsetDateTime updatedAt;
}
