package com.avies.backend.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Kết quả phân trang ổn định, không phụ thuộc cấu trúc nội bộ của Spring Data.
 */
@Getter
@Builder
public class PageResponse<T> {
    /**
     * Các phần tử thuộc trang hiện tại.
     */
    private final List<T> items;

    /**
     * Chỉ số trang bắt đầu từ không.
     */
    private final int page;

    /**
     * Kích thước trang được yêu cầu.
     */
    private final int size;

    /**
     * Tổng số phần tử trong phạm vi quyền và bộ lọc.
     */
    private final long totalElements;

    /**
     * Tổng số trang tương ứng.
     */
    private final int totalPages;
}
