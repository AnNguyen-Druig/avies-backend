package com.avies.backend.dto.response;

import com.avies.backend.entity.enums.*;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Thông tin rút gọn trong danh sách ngân hàng câu hỏi.
 */
@Getter
@Builder
public class QuestionSummaryResponse {
    /**
     * Định danh câu hỏi.
     */
    private final Long id;

    /**
     * Định danh môn học.
     */
    private final Long subjectId;

    /**
     * Tên môn học.
     */
    private final String subjectName;

    /**
     * Phần đầu nội dung, tối đa 200 ký tự Unicode.
     */
    private final String contentPreview;

    /**
     * Nhãn chủ đề của câu hỏi.
     */
    private final Set<String> topics;

    /**
     * Mức Bloom hiện tại.
     */
    private final BloomLevel bloomLevel;

    /**
     * Nguồn tạo câu hỏi.
     */
    private final QuestionSourceType sourceType;

    /**
     * Trạng thái xét duyệt hiện tại.
     */
    private final QuestionStatus status;

    /**
     * Định danh tác giả.
     */
    private final Long createdById;

    /**
     * Tên tác giả để hiển thị.
     */
    private final String createdByName;

    /**
     * Thời điểm tạo.
     */
    private final OffsetDateTime createdAt;

    /**
     * Thời điểm lưu trữ hoặc null.
     */
    private final OffsetDateTime archivedAt;

    /**
     * Cho biết rubric tồn tại, không khẳng định rubric đã hợp lệ.
     */
    private final boolean hasRubric;
}
