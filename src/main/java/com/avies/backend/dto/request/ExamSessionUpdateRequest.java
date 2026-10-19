package com.avies.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import lombok.Getter;
import java.time.OffsetDateTime;

/** PATCH phân biệt trường bỏ qua với trường gửi; mọi giá trị null tường minh đều bị từ chối. */
@Getter
public class ExamSessionUpdateRequest {
    /** Tiêu đề phiên thi, tối đa 255 ký tự. Trường không gửi sẽ giữ nguyên. */
    @Size(max = 255)
    @JsonDeserialize(using = ExamSessionJson.Text.class)
    private String title;

    /** Thời điểm bắt đầu dự kiến, bắt buộc có offset. Trường không gửi sẽ giữ nguyên. */
    @JsonDeserialize(using = ExamSessionJson.Timestamp.class)
    private OffsetDateTime scheduledStartAt;

    /** Thời điểm kết thúc dự kiến, bắt buộc có offset. Trường không gửi sẽ giữ nguyên. */
    @JsonDeserialize(using = ExamSessionJson.Timestamp.class)
    private OffsetDateTime scheduledEndAt;

    /** Số phút dự kiến cho mỗi sinh viên, phải dương. Trường không gửi sẽ giữ nguyên. */
    @Positive
    @JsonDeserialize(using = ExamSessionJson.IntegerValue.class)
    private Integer slotDurationMinutes;

    /** Số câu hỏi chính tối đa, phải dương. Trường không gửi sẽ giữ nguyên. */
    @Positive
    @JsonDeserialize(using = ExamSessionJson.IntegerValue.class)
    private Integer maxMainQuestions;

    /** Số câu hỏi phụ tối đa, cho phép bằng không. Trường không gửi sẽ giữ nguyên. */
    @PositiveOrZero
    @JsonDeserialize(using = ExamSessionJson.IntegerValue.class)
    private Integer maxFollowUps;

    /** Đánh dấu title xuất hiện trong JSON. */
    @JsonIgnore
    private boolean titlePresent;

    /** Đánh dấu scheduledStartAt xuất hiện trong JSON. */
    @JsonIgnore
    private boolean scheduledStartAtPresent;

    /** Đánh dấu scheduledEndAt xuất hiện trong JSON. */
    @JsonIgnore
    private boolean scheduledEndAtPresent;

    /** Đánh dấu slotDurationMinutes xuất hiện trong JSON. */
    @JsonIgnore
    private boolean slotDurationMinutesPresent;

    /** Đánh dấu maxMainQuestions xuất hiện trong JSON. */
    @JsonIgnore
    private boolean maxMainQuestionsPresent;

    /** Đánh dấu maxFollowUps xuất hiện trong JSON. */
    @JsonIgnore
    private boolean maxFollowUpsPresent;

    /** Ghi nhận trường title; null không được dùng để xóa dữ liệu bắt buộc. */
    @JsonSetter(value = "title", nulls = Nulls.FAIL)
    public void setTitle(String value) {
        titlePresent = true;
        title = value;
    }

    /** Ghi nhận trường scheduledStartAt; null không được dùng để xóa dữ liệu bắt buộc. */
    @JsonSetter(value = "scheduledStartAt", nulls = Nulls.FAIL)
    public void setScheduledStartAt(OffsetDateTime value) {
        scheduledStartAtPresent = true;
        scheduledStartAt = value;
    }

    /** Ghi nhận trường scheduledEndAt; null không được dùng để xóa dữ liệu bắt buộc. */
    @JsonSetter(value = "scheduledEndAt", nulls = Nulls.FAIL)
    public void setScheduledEndAt(OffsetDateTime value) {
        scheduledEndAtPresent = true;
        scheduledEndAt = value;
    }

    /** Ghi nhận trường slotDurationMinutes; null không được dùng để xóa dữ liệu bắt buộc. */
    @JsonSetter(value = "slotDurationMinutes", nulls = Nulls.FAIL)
    public void setSlotDurationMinutes(Integer value) {
        slotDurationMinutesPresent = true;
        slotDurationMinutes = value;
    }

    /** Ghi nhận trường maxMainQuestions; null không được dùng để xóa dữ liệu bắt buộc. */
    @JsonSetter(value = "maxMainQuestions", nulls = Nulls.FAIL)
    public void setMaxMainQuestions(Integer value) {
        maxMainQuestionsPresent = true;
        maxMainQuestions = value;
    }

    /** Ghi nhận trường maxFollowUps; null không được dùng để xóa dữ liệu bắt buộc. */
    @JsonSetter(value = "maxFollowUps", nulls = Nulls.FAIL)
    public void setMaxFollowUps(Integer value) {
        maxFollowUpsPresent = true;
        maxFollowUps = value;
    }

    /** Yêu cầu PATCH phải chứa ít nhất một trường được phép cập nhật. */
    @AssertTrue
    @JsonIgnore
    public boolean isAnyFieldPresent() {
        return titlePresent || scheduledStartAtPresent || scheduledEndAtPresent || slotDurationMinutesPresent || maxMainQuestionsPresent || maxFollowUpsPresent;
    }

    /** Không cho gửi subjectId, lecturerId, status hoặc các cờ presence nội bộ. */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Trường không được hỗ trợ trong yêu cầu phiên thi.");
    }
}
