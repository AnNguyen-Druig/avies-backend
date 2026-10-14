package com.avies.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/** Tham số phân trang cho danh sách phân công và dropdown môn của giảng viên. */
@Getter
@Setter
public class AssignmentPageRequest {
    /** Chỉ số trang bắt đầu từ 0, giới hạn để tránh truy vấn offset quá lớn. */
    @Min(0)
    @Max(1000000)
    private int page;

    /** Số bản ghi mỗi trang, mặc định 20 và tối đa 100. */
    @Min(1)
    @Max(100)
    private int size = 20;
}
