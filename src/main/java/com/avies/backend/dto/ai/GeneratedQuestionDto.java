package com.avies.backend.dto.ai;

import com.avies.backend.entity.enums.BloomLevel;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cấu trúc dữ liệu câu hỏi và rubric do AI sinh ra.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class GeneratedQuestionDto {

    String content;
    String referenceAnswer;
    BloomLevel bloomLevel;

    @Builder.Default
    Set<String> topics = new LinkedHashSet<>();

    Integer answerTimeLimitSeconds;
    GeneratedRubricDto rubric;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static class GeneratedRubricDto {
        String title;
        String description;

        @Builder.Default
        List<GeneratedCriterionDto> criteria = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static class GeneratedCriterionDto {
        String criterionText;
        BigDecimal pointWeight;

        @Builder.Default
        Boolean isMandatory = false;

        @Builder.Default
        Integer displayOrder = 1;
    }
}
