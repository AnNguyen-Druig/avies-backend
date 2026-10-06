package com.avies.backend.dto.request;

import com.avies.backend.entity.enums.BloomLevel;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Dữ liệu tạo câu hỏi thủ công; nguồn, trạng thái và tác giả do backend gán.
 */
@Getter
@Setter
public class QuestionCreationRequest extends QuestionWriteRequest {
    /**
     * Môn học tồn tại mà người tạo được phép quản lý.
     */
    @NotNull
    @Positive
    private Long subjectId;

    /**
     * Nội dung văn bản bắt buộc của câu hỏi.
     */
    @NotBlank
    @Size(max = 10000)
    private String content;

    /**
     * Đáp án tham khảo tùy chọn cho người chấm.
     */
    @Size(max = 20000)
    private String referenceAnswer;

    /**
     * Danh sách nhãn chủ đề, được chuẩn hóa và loại trùng khi lưu.
     */
    @NotNull
    @Size(max = 10)
    private List<@NotBlank @Size(max = 100) String> topics = new ArrayList<>();

    /**
     * Mức Bloom có thể chưa xác định khi tạo bản nháp.
     */
    private BloomLevel bloomLevel;

    /**
     * Thời gian dự kiến trả lời câu chính, tính bằng giây.
     */
    @Positive
    private Integer answerTimeLimitSeconds;
}
