package com.avies.backend.dto.response;

import com.avies.backend.entity.enums.ExamSessionStatus;
import lombok.Builder;
import lombok.Getter;
import java.time.OffsetDateTime;

/** DTO phiên thi không chứa điểm, câu trả lời hoặc thông tin riêng của sinh viên. */
@Getter
@Builder
public class ExamSessionResponse {
    /** Định danh phiên thi. */
    private final Long id;

    /** Tiêu đề phiên thi. */
    private final String title;

    /** ID giảng viên sở hữu; không kèm dữ liệu tài khoản nhạy cảm. */
    private final Long lecturerId;

    /** ID môn học. */
    private final Long subjectId;

    /** Mã môn để hiển thị. */
    private final String subjectCode;

    /** Tên môn để hiển thị. */
    private final String subjectName;

    /** Trạng thái phiên thi. */
    private final ExamSessionStatus status;

    /** Bắt đầu dự kiến; có thể null với dữ liệu cũ. */
    private final OffsetDateTime scheduledStartAt;

    /** Kết thúc dự kiến; có thể null với dữ liệu cũ. */
    private final OffsetDateTime scheduledEndAt;

    /** Số phút cho mỗi sinh viên; có thể null với dữ liệu cũ. */
    private final Integer slotDurationMinutes;

    /** Số câu chính tối đa. */
    private final Integer maxMainQuestions;

    /** Số câu phụ tối đa. */
    private final Integer maxFollowUps;

    /** Thời điểm tạo phiên. */
    private final OffsetDateTime createdAt;
}
