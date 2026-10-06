package com.avies.backend.dto.request;

import com.avies.backend.entity.enums.BloomLevel;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Cập nhật từng phần, phân biệt trường không gửi với trường được xóa bằng null.
 */
@Getter
public class QuestionUpdateRequest extends QuestionWriteRequest {
    /**
     * Phiên bản người dùng đã đọc, dùng để phát hiện dữ liệu cũ.
     */
    @Setter
    @NotNull
    @PositiveOrZero
    private Long expectedVersion;

    /**
     * Nội dung mới; không được null hoặc trắng nếu có gửi.
     */
    @Size(max = 10000)
    private String content;

    /**
     * Đáp án mới; null có nghĩa xóa đáp án cũ.
     */
    @Size(max = 20000)
    private String referenceAnswer;

    /**
     * Danh sách thay thế toàn bộ chủ đề; danh sách rỗng xóa mọi chủ đề.
     */
    @Size(max = 10)
    private List<@NotBlank @Size(max = 100) String> topics;

    /**
     * Mức Bloom mới; null cho phép bỏ mức Bloom của bản nháp.
     */
    private BloomLevel bloomLevel;

    /**
     * Thời gian mới; null bỏ giới hạn thời gian dự kiến.
     */
    @Positive
    private Integer answerTimeLimitSeconds;

    /**
     * Đánh dấu trường nội dung xuất hiện trong JSON.
     */
    @JsonIgnore
    private boolean contentPresent;

    /**
     * Đánh dấu trường đáp án xuất hiện trong JSON.
     */
    @JsonIgnore
    private boolean referenceAnswerPresent;

    /**
     * Đánh dấu trường chủ đề xuất hiện trong JSON.
     */
    @JsonIgnore
    private boolean topicsPresent;

    /**
     * Đánh dấu trường Bloom xuất hiện trong JSON.
     */
    @JsonIgnore
    private boolean bloomLevelPresent;

    /**
     * Đánh dấu trường thời gian xuất hiện trong JSON.
     */
    @JsonIgnore
    private boolean answerTimeLimitSecondsPresent;

    /**
     * Ghi nhận nội dung được gửi, kể cả null để kiểm tra tính hợp lệ.
     */
    public void setContent(String value) {
        contentPresent = true;
        content = value;
    }

    /**
     * Ghi nhận yêu cầu thay thế hoặc xóa đáp án.
     */
    public void setReferenceAnswer(String value) {
        referenceAnswerPresent = true;
        referenceAnswer = value;
    }

    /**
     * Ghi nhận yêu cầu thay thế toàn bộ chủ đề.
     */
    public void setTopics(List<String> value) {
        topicsPresent = true;
        topics = value;
    }

    /**
     * Ghi nhận yêu cầu thay thế hoặc bỏ mức Bloom.
     */
    public void setBloomLevel(BloomLevel value) {
        bloomLevelPresent = true;
        bloomLevel = value;
    }

    /**
     * Ghi nhận yêu cầu thay thế hoặc bỏ thời gian trả lời.
     */
    public void setAnswerTimeLimitSeconds(Integer value) {
        answerTimeLimitSecondsPresent = true;
        answerTimeLimitSeconds = value;
    }

    /**
     * Bảo đảm PATCH có ít nhất một trường nghiệp vụ và không xóa trường bắt buộc.
     */
    @AssertTrue(message = "PATCH phải có trường cần sửa; nội dung và danh sách chủ đề không được null.")
    @JsonIgnore
    public boolean isPatchValid() {
        return (contentPresent || referenceAnswerPresent || topicsPresent || bloomLevelPresent
                || answerTimeLimitSecondsPresent)
                && (!contentPresent || (content != null && !content.isBlank()))
                && (!topicsPresent || topics != null);
    }
}
