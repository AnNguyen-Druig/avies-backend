package com.avies.backend.dto.response;

import com.avies.backend.entity.enums.*;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Chi tiết câu hỏi dành cho người có quyền, không tuần tự hóa trực tiếp entity.
 */
@Getter
@Builder
public class QuestionResponse {
    /**
     * Định danh câu hỏi.
     */
    private final Long id;

    /**
     * Định danh môn học.
     */
    private final Long subjectId;

    /**
     * Tên môn học để hiển thị.
     */
    private final String subjectName;

    /**
     * Nội dung đầy đủ.
     */
    private final String content;

    /**
     * Đáp án tham khảo, không công khai cho sinh viên.
     */
    private final String referenceAnswer;

    /**
     * Những chủ đề gắn với câu hỏi.
     */
    private final Set<String> topics;

    /**
     * Mức độ nhận thức hoặc null ở bản nháp.
     */
    private final BloomLevel bloomLevel;

    /**
     * Thời gian dự kiến tính bằng giây.
     */
    private final Integer answerTimeLimitSeconds;

    /**
     * Nguồn tạo; dữ liệu cũ chưa xác minh có thể chưa có giá trị.
     */
    private final QuestionSourceType sourceType;

    /**
     * Trạng thái duyệt; dữ liệu cũ chưa phân loại có thể chưa có giá trị.
     */
    private final QuestionStatus status;

    /**
     * Tác giả của câu hỏi.
     */
    private final Long createdById;

    /**
     * Tên hiển thị của tác giả.
     */
    private final String createdByName;

    /**
     * Người cập nhật gần nhất.
     */
    private final Long updatedById;

    /**
     * Thời điểm tạo theo múi giờ.
     */
    private final OffsetDateTime createdAt;

    /**
     * Thời điểm cập nhật gần nhất.
     */
    private final OffsetDateTime updatedAt;

    /**
     * Thời điểm lưu trữ hoặc null.
     */
    private final OffsetDateTime archivedAt;

    /**
     * Phiên bản cần gửi lại khi cập nhật.
     */
    private final Long version;

    /**
     * Rubric riêng của câu hỏi hoặc null nếu chưa có.
     */
    private final Long rubricId;
}
