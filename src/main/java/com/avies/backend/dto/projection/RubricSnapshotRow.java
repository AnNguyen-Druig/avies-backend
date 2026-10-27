package com.avies.backend.dto.projection;

import java.math.BigDecimal;
import java.time.Instant;

/** Dòng rubric và tiêu chí đọc mới dưới khóa; không hydrate entity đã cache. */
public interface RubricSnapshotRow {
    /** Định danh rubric nguồn. */ Long getRubricId();
    /** Môn sở hữu rubric. */ Long getSubjectId();
    /** Tiêu đề nguồn. */ String getTitle();
    /** Mô tả nguồn, cho phép trống. */ String getDescription();
    /** TIMESTAMPTZ scalar do Hibernate trả về Instant, dùng để đối chiếu nguồn. */ Instant getUpdatedAt();
    /** Định danh tiêu chí, null khi rubric chưa có tiêu chí. */ Long getCriterionId();
    /** Nội dung hướng dẫn chấm. */ String getCriterionText();
    /** Điểm tối đa chính xác bằng BigDecimal. */ BigDecimal getPointWeight();
    /** Cờ nguồn được giữ để truy xuất, không kích hoạt quy tắc tự trượt. */ Boolean getMandatory();
    /** Thứ tự tiêu chí trong thang chấm. */ Integer getDisplayOrder();
}
