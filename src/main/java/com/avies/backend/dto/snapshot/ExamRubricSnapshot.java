package com.avies.backend.dto.snapshot;

import java.math.BigDecimal;
import java.util.List;

/** Hợp đồng JSON nội bộ bất biến của rubric đã chốt; tuyệt đối không trả qua API sinh viên. */
public record ExamRubricSnapshot(
        /** Phiên bản cấu trúc JSON, khác version của rubric nguồn. */ int schemaVersion,
        /** ID chỉ phục vụ truy xuất; đọc snapshot không cần rubric nguồn còn tồn tại. */ Long sourceRubricId,
        /** Môn học tại thời điểm chốt. */ Long subjectId,
        /** Tiêu đề nguyên bản. */ String title,
        /** Hướng dẫn chung nguyên bản. */ String description,
        /** Thời điểm cập nhật nguồn ở định dạng ISO-8601, có thể chưa có ở dữ liệu cũ. */ String sourceUpdatedAt,
        /** Tiêu chí đầy đủ cùng điểm tối đa, theo thứ tự hiển thị. */ List<Criterion> criteria) {
    /** Chép danh sách để caller không sửa cấu trúc của bản chụp sau khi đọc. */
    public ExamRubricSnapshot {
        criteria = criteria == null ? null : List.copyOf(criteria);
    }

    /** Bản chụp một tiêu chí; điểm vẫn là BigDecimal, không chuyển thành số thực gần đúng. */
    public record Criterion(
            /** ID nguồn để đối chiếu, không phải khóa ngoại tới tiêu chí hiện hành. */ Long sourceCriterionId,
            /** Mô tả cách chấm. */ String text,
            /** Điểm tối đa. */ BigDecimal maxScore,
            /** Giữ cờ nguồn nhưng MVP không áp dụng cơ chế tự trượt. */ Boolean mandatory,
            /** Thứ tự hiển thị. */ Integer displayOrder) { }
}
