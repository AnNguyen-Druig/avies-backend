package com.avies.backend.exam;

import com.avies.backend.entity.enums.BloomLevel;
import com.avies.backend.service.support.ExamQuestionEligibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.util.List;
import static com.avies.backend.exam.ExamQuestionTestData.row;
import static org.assertj.core.api.Assertions.*;

/** Kiểm tra các điều kiện sử dụng câu và rubric mà không sửa chữa dữ liệu đầu vào. */
class ExamQuestionEligibilityTest {
    /** Validator không có trạng thái hoặc repository. */
    private final ExamQuestionEligibility eligibility = new ExamQuestionEligibility();

    /** Dùng enum hiện hành đủ sáu mức, không giữ hardcode bốn mức từ tài liệu cũ. */
    @ParameterizedTest
    @EnumSource(BloomLevel.class)
    void supportsEveryCurrentBloomEnum(BloomLevel bloom) {
        assertThat(eligibility.inspect(List.of(row(1, bloom)), 1L).candidates()).containsKey(1L);
    }

    /** Subject/status/archived/Bloom/content sai bị loại trước bước rubric. */
    @ParameterizedTest
    @ValueSource(strings = {"subject", "draft", "pending", "rejected", "null_status", "archived", "null_bloom",
            "unknown_bloom", "blank_content", "long_content", "nul_content", "null_version"})
    void excludesIneligibleQuestionMetadata(String issue) {
        var row = row(1, BloomLevel.REMEMBER);
        switch (issue) {
            case "subject" -> row.setSubjectId(2L);
            case "draft" -> row.setQuestionStatus("DRAFT");
            case "pending" -> row.setQuestionStatus("PENDING_REVIEW");
            case "rejected" -> row.setQuestionStatus("REJECTED");
            case "null_status" -> row.setQuestionStatus(null);
            case "archived" -> row.setArchived(true);
            case "null_bloom" -> row.setBloomLevel(null);
            case "unknown_bloom" -> row.setBloomLevel("UNKNOWN");
            case "blank_content" -> row.setContent("  ");
            case "long_content" -> row.setContent("q".repeat(10001));
            case "nul_content" -> row.setContent("a\0b");
            case "null_version" -> row.setQuestionVersion(null);
        }
        var pool = eligibility.inspect(List.of(row), 1L);
        assertThat(pool.candidates()).isEmpty();
        assertThat(pool.basicCount()).isZero();
    }

    /** Rubric thiếu, rỗng, điểm không dùng được hoặc thứ tự lỗi đều không được chọn. */
    @ParameterizedTest
    @ValueSource(strings = {"rubric", "empty", "blank", "nul", "null_weight", "zero", "negative",
            "over_ten", "sum", "precision", "order", "null_order", "null_mandatory"})
    void excludesInvalidRubricsWithoutChangingSource(String issue) {
        var row = row(1, BloomLevel.APPLY);
        switch (issue) {
            case "rubric" -> row.setRubricId(null);
            case "empty" -> row.setCriterionId(null);
            case "blank" -> row.setCriterionText(" ");
            case "nul" -> row.setCriterionText("a\0b");
            case "null_weight" -> row.setPointWeight(null);
            case "zero" -> row.setPointWeight(BigDecimal.ZERO);
            case "negative" -> row.setPointWeight(new BigDecimal("-1"));
            case "over_ten" -> row.setPointWeight(new BigDecimal("11"));
            case "sum" -> row.setPointWeight(new BigDecimal("9.99"));
            case "precision" -> row.setPointWeight(new BigDecimal("9.999"));
            case "order" -> row.setDisplayOrder(0);
            case "null_order" -> row.setDisplayOrder(null);
            case "null_mandatory" -> row.setMandatory(null);
        }
        var pool = eligibility.inspect(List.of(row), 1L);
        assertThat(pool.candidates()).isEmpty();
        assertThat(pool.basicCount()).isEqualTo(1);
        if (issue.equals("sum")) assertThat(row.getPointWeight()).isEqualByComparingTo("9.99");
    }

    /** Tổng bằng 10 chính xác với điểm lẻ; không dùng double hoặc làm tròn để hợp thức hóa rubric sai. */
    @Test
    void acceptsFractionalScaleAndGroupsJoinedCriteriaIntoOneQuestion() {
        var first = row(1, BloomLevel.UNDERSTAND);
        first.setPointWeight(new BigDecimal("3.33"));
        var second = row(1, BloomLevel.UNDERSTAND);
        second.setCriterionId(2001L); second.setDisplayOrder(3); second.setPointWeight(new BigDecimal("6.67"));
        second.setMandatory(true);
        var candidate = eligibility.inspect(List.of(first, second), 1L).candidates().get(1L);
        assertThat(candidate.criteria()).hasSize(2);
        assertThat(candidate.criteria().getLast().mandatory()).isTrue();
    }

    /** Cùng order hoặc ID tiêu chí lặp không thể được xem là rubric hợp lệ dù tổng đủ 10. */
    @ParameterizedTest
    @ValueSource(strings = {"order", "id"})
    void refusesDuplicateCriterionIdentityOrOrder(String duplicate) {
        var first = row(1, BloomLevel.ANALYZE); first.setPointWeight(new BigDecimal("5"));
        var second = row(1, BloomLevel.ANALYZE); second.setPointWeight(new BigDecimal("5"));
        if (duplicate.equals("order")) second.setCriterionId(2000L);
        else second.setDisplayOrder(2);
        assertThat(eligibility.inspect(List.of(first, second), 1L).candidates()).isEmpty();
    }
}
