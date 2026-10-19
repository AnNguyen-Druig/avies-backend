package com.avies.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/** Phân trang dùng chung cho danh sách sinh viên và lịch thi, giữ quy ước bắt đầu từ trang 0. */
@Getter
@Setter
public class ExamAssignmentPageRequest {
    /** Trang tối đa một triệu để tránh offset vượt miền số nguyên. */
    @Min(0) @Max(1000000)
    private int page;
    /** Mặc định 20, mỗi trang từ 1 đến 100 bản ghi. */
    @Min(1) @Max(100)
    private int size = 20;
}
