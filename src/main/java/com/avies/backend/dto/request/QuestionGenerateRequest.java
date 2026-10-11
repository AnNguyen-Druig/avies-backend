package com.avies.backend.dto.request;

import com.avies.backend.entity.enums.BloomLevel;
import jakarta.validation.constraints.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

/**
 * Yêu cầu sinh câu hỏi vấn đáp tự động bằng AI qua RAG trên tài liệu môn học.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class QuestionGenerateRequest {

    /**
     * Định danh môn học cần sinh câu hỏi (bắt buộc).
     */
    @NotNull(message = "Mã môn học không được để trống.")
    @Positive(message = "Mã môn học phải là số nguyên dương.")
    Long subjectId;

    /**
     * Từ khóa hoặc chủ đề cần sinh câu hỏi (bắt buộc).
     */
    @NotBlank(message = "Từ khóa tìm kiếm không được để trống.")
    @Size(max = 255, message = "Từ khóa không được vượt quá 255 ký tự.")
    String keyword;

    /**
     * Mức độ nhận thức theo thang Bloom mong muốn (tùy chọn: REMEMBER, UNDERSTAND, APPLY, ANALYZE).
     * Nếu không truyền, AI sẽ tự đề xuất mức độ phù hợp nhất dựa trên nội dung tài liệu.
     */
    BloomLevel bloomLevel;

    /**
     * Số lượng câu hỏi cần sinh (mặc định 1 nếu không truyền, tối đa 10).
     */
    @Min(value = 1, message = "Số lượng câu hỏi tối thiểu là 1.")
    @Max(value = 10, message = "Số lượng câu hỏi tối đa mỗi lần sinh là 10.")
    @Builder.Default
    Integer count = 1;

    /**
     * Danh sách mã tài liệu cụ thể nếu muốn giới hạn phạm vi tìm kiếm (tùy chọn).
     */
    List<Long> materialIds;
}
