package com.avies.backend.service.support;

import com.avies.backend.entity.enums.BloomLevel;
import com.avies.backend.dto.projection.QuestionSelectionRow;
import com.avies.backend.dto.snapshot.ExamRubricSnapshot;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;

/** Kiểm tra đọc-only: không chữa rubric AI sai, không đổi nội dung hoặc cấu trúc câu hỏi. */
@Component
public class ExamQuestionEligibility {
    /** Bản chụp tiêu chí nội bộ để so sánh trước/sau khóa; không được serialize ra API. */
    public record Criterion(
            /** ID tiêu chí hiện có. */ Long id,
            /** Mô tả nguyên bản. */ String text,
            /** Trọng số đã chuẩn hóa scale để so sánh giá trị. */ BigDecimal weight,
            /** Cờ dữ liệu, chưa áp dụng cơ chế tự trượt. */ Boolean mandatory,
            /** Thứ tự hiển thị trong rubric. */ Integer order) { }

    /** Ảnh dữ liệu đọc trong transaction, không phải snapshot lịch sử được lưu thêm vào DB. */
    public record Candidate(
            /** ID câu có thể chọn. */ Long id,
            /** Bloom thuộc enum đang chạy. */ BloomLevel bloom,
            /** Version nguồn. */ Long version,
            /** Nội dung dùng phát hiện thay đổi. */ String content,
            /** Đáp án nguyên bản phải được so sánh lại sau khóa. */ String referenceAnswer,
            /** Giới hạn trả lời phải được chốt cùng nội dung câu. */ Integer answerTimeLimitSeconds,
            /** ID rubric được kiểm tra. */ Long rubricId,
            /** Tiêu chí bất biến theo ID. */ List<Criterion> criteria) { }

    /** Pool hợp lệ và số câu đủ điều kiện cơ bản nhưng rubric chưa dùng được. */
    public record Pool(
            /** Câu hợp lệ có thể random. */ Map<Long, Candidate> candidates,
            /** Số câu đúng môn/status/Bloom/content trước bước kiểm tra rubric. */ int basicCount) { }

    /** Gom các dòng join thành câu duy nhất, loại mọi rubric thiếu/sai thang điểm 10. */
    public Pool inspect(List<QuestionSelectionRow> rows, Long subjectId) {
        return inspect(rows, subjectId, null);
    }

    /** Khi đã có rubric phiên hợp lệ, không phụ thuộc rubric môn hiện hành để lập bộ cho sinh viên sau. */
    public Pool inspect(List<QuestionSelectionRow> rows, Long subjectId, ExamRubricSnapshot snapshot) {
        Map<Long, List<QuestionSelectionRow>> grouped = new TreeMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.getQuestionId(), ignored -> new ArrayList<>()).add(row));
        Map<Long, Candidate> valid = new LinkedHashMap<>();
        int basicCount = 0;
        for (var entry : grouped.entrySet()) {
            var first = entry.getValue().getFirst();
            BloomLevel bloom = supportedBloom(first.getBloomLevel());
            if (!Objects.equals(subjectId, first.getSubjectId()) || !"APPROVED".equals(first.getQuestionStatus())
                    || !Boolean.FALSE.equals(first.getArchived()) || bloom == null || !usableText(first.getContent())
                    || first.getContent().length() > 10000 || first.getQuestionVersion() == null || first.getQuestionVersion() < 0
                    || (first.getAnswerTimeLimitSeconds() != null && first.getAnswerTimeLimitSeconds() <= 0))
                continue;
            basicCount++;
            List<Criterion> criteria = snapshot == null ? criteria(entry.getValue()) : snapshot.criteria().stream()
                    .map(c -> new Criterion(c.sourceCriterionId(), c.text(), c.maxScore().stripTrailingZeros(),
                            c.mandatory(), c.displayOrder())).toList();
            Long rubricId = snapshot == null ? first.getRubricId() : snapshot.sourceRubricId();
            if (rubricId == null || criteria.isEmpty()) continue;
            valid.put(entry.getKey(), new Candidate(entry.getKey(), bloom, first.getQuestionVersion(),
                    first.getContent(), first.getReferenceAnswer(), first.getAnswerTimeLimitSeconds(), rubricId, criteria));
        }
        return new Pool(Collections.unmodifiableMap(valid), basicCount);
    }

    /** Không dùng parser từ JSON để suy diễn các giá trị Bloom trống hoặc không thuộc enum. */
    private BloomLevel supportedBloom(String name) {
        if (name == null) return null;
        try { return BloomLevel.valueOf(name); }
        catch (IllegalArgumentException exception) { return null; }
    }

    /** Điểm dương, tối đa hai số thập phân, thứ tự duy nhất và tổng đúng 10 bằng BigDecimal. */
    private List<Criterion> criteria(List<QuestionSelectionRow> rows) {
        List<Criterion> result = new ArrayList<>();
        Set<Integer> orders = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var row : rows) {
            BigDecimal weight = row.getPointWeight();
            if (row.getCriterionId() == null || !ids.add(row.getCriterionId()) || !usableText(row.getCriterionText())
                    || weight == null || weight.signum() <= 0 || weight.compareTo(BigDecimal.TEN) > 0
                    || weight.stripTrailingZeros().scale() > 2 || row.getDisplayOrder() == null
                    || row.getDisplayOrder() < 1 || !orders.add(row.getDisplayOrder()) || row.getMandatory() == null)
                return List.of();
            total = total.add(weight);
            result.add(new Criterion(row.getCriterionId(), row.getCriterionText(), weight.stripTrailingZeros(),
                    row.getMandatory(), row.getDisplayOrder()));
        }
        if (total.compareTo(BigDecimal.TEN) != 0) return List.of();
        result.sort(Comparator.comparing(Criterion::id));
        return List.copyOf(result);
    }

    /** Không để câu hoặc tiêu chí chỉ có khoảng trắng/NUL trở thành nội dung thi. */
    private boolean usableText(String value) {
        return value != null && !value.isBlank() && value.indexOf('\0') < 0;
    }
}
