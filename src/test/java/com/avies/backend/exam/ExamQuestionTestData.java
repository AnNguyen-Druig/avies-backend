package com.avies.backend.exam;

import com.avies.backend.entity.enums.BloomLevel;
import com.avies.backend.dto.projection.QuestionSelectionRow;
import com.avies.backend.service.support.ExamQuestionEligibility;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;

/** Dữ liệu thuần cho unit test eligibility/selection, không dùng nguồn câu hoặc rubric thật. */
final class ExamQuestionTestData {
    /** Không cho khởi tạo helper. */
    private ExamQuestionTestData() { }

    /** Projection giả có getter đúng tên, không phụ thuộc Mockito hoặc dialect DB. */
    @Getter @Setter @Builder
    static class Row implements QuestionSelectionRow {
        /** ID câu. */ private Long questionId;
        /** Môn. */ private Long subjectId;
        /** Version. */ private Long questionVersion;
        /** Status. */ private String questionStatus;
        /** Bloom dạng raw. */ private String bloomLevel;
        /** Cờ lưu trữ. */ private Boolean archived;
        /** Nội dung. */ private String content;
        /** Rubric. */ private Long rubricId;
        /** Tiêu chí. */ private Long criterionId;
        /** Mô tả. */ private String criterionText;
        /** Điểm. */ private BigDecimal pointWeight;
        /** Cờ metadata. */ private Boolean mandatory;
        /** Thứ tự. */ private Integer displayOrder;
    }

    /** Một câu APPROVED đầy đủ có rubric một tiêu chí tổng 10. */
    static Row row(long id, BloomLevel bloom) {
        return Row.builder().questionId(id).subjectId(1L).questionVersion(0L).questionStatus("APPROVED")
                .bloomLevel(bloom.name()).archived(false).content("Câu hỏi " + id).rubricId(id + 100)
                .criterionId(id + 1000).criterionText("Tiêu chí " + id).pointWeight(new BigDecimal("10.00"))
                .mandatory(false).displayOrder(1).build();
    }

    /** Candidate được tạo qua validator thật thay vì bỏ qua dữ liệu rubric. */
    static ExamQuestionEligibility.Candidate candidate(long id, BloomLevel bloom) {
        return new ExamQuestionEligibility().inspect(List.of(row(id, bloom)), 1L).candidates().get(id);
    }
}
