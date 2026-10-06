package com.avies.backend.dto.request;

import com.avies.backend.entity.enums.*;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Bộ lọc danh sách câu hỏi; mọi bộ lọc luôn nằm trong phạm vi quyền của người gọi.
 */
@Getter
@Setter
public class QuestionSearchRequest {
    /**
     * Lọc câu hỏi theo môn học.
     */
    @Positive
    private Long subjectId;

    /**
     * Lọc theo một chủ đề, không phân biệt hoa thường.
     */
    @Size(max = 100)
    private String topic;

    /**
     * Lọc theo mức độ nhận thức.
     */
    private BloomLevel bloomLevel;

    /**
     * Lọc theo trạng thái xét duyệt.
     */
    private QuestionStatus status;

    /**
     * Lọc theo nguồn tạo.
     */
    private QuestionSourceType sourceType;

    /**
     * Từ khóa tìm trong nội dung câu hỏi.
     */
    @Size(max = 200)
    private String keyword;

    /**
     * Lọc theo tác giả nhưng không mở rộng quyền truy cập.
     */
    @Positive
    private Long createdById;

    /**
     * Chọn câu lưu trữ; mặc định chỉ trả câu đang hoạt động.
     */
    private boolean archived;

    /**
     * Chỉ số trang bắt đầu từ không, có giới hạn để tránh offset quá lớn.
     */
    @Min(0)
    @Max(1000000)
    private int page;

    /**
     * Số phần tử tối đa trên mỗi trang.
     */
    @Min(1)
    @Max(100)
    private int size = 20;

    /**
     * Chỉ cho phép sắp xếp trên các cột đã công bố.
     */
    @Pattern(regexp = "(id|createdAt|updatedAt),(asc|desc)")
    private String sort = "createdAt,desc";
}
