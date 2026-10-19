package com.avies.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

/** Dữ liệu tạo phiên thi; chủ sở hữu và trạng thái chỉ do backend gán. */
@Getter
@Setter
public class ExamSessionCreationRequest {
    /** Tiêu đề phiên thi, tối đa 255 ký tự. */
    @NotBlank
    @Size(max = 255)
    @JsonDeserialize(using = ExamSessionJson.Text.class)
    private String title;

    /** Môn đã phân công cho giảng viên đăng nhập. */
    @NotNull
    @Positive
    @JsonDeserialize(using = ExamSessionJson.Identifier.class)
    private Long subjectId;

    /** Thời điểm bắt đầu dự kiến, bắt buộc có offset. */
    @NotNull
    @JsonDeserialize(using = ExamSessionJson.Timestamp.class)
    private OffsetDateTime scheduledStartAt;

    /** Thời điểm kết thúc dự kiến, bắt buộc có offset. */
    @NotNull
    @JsonDeserialize(using = ExamSessionJson.Timestamp.class)
    private OffsetDateTime scheduledEndAt;

    /** Số phút dự kiến cho mỗi sinh viên, phải dương. */
    @NotNull
    @Positive
    @JsonDeserialize(using = ExamSessionJson.IntegerValue.class)
    private Integer slotDurationMinutes;

    /** Số câu hỏi chính tối đa, phải dương. */
    @NotNull
    @Positive
    @JsonDeserialize(using = ExamSessionJson.IntegerValue.class)
    private Integer maxMainQuestions;

    /** Số câu hỏi phụ tối đa, cho phép bằng không. */
    @NotNull
    @PositiveOrZero
    @JsonDeserialize(using = ExamSessionJson.IntegerValue.class)
    private Integer maxFollowUps;

    /** Từ chối mọi trường ngoài hợp đồng, gồm lecturerId, status và điểm. */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Trường không được hỗ trợ trong yêu cầu phiên thi.");
    }
}
