package com.avies.backend.dto.response;

import lombok.Builder;
import lombok.Getter;
import java.time.OffsetDateTime;
import com.avies.backend.entity.enums.StudentExamStatus;

/** Sinh viên và lịch trong phiên; không trả câu hỏi, lời giải, rubric hoặc bằng chứng thi. */
@Getter
@Builder
public class StudentExamAssignmentResponse {
    /** ID StudentExam, dùng khi thu hồi. */
    private final Long id;

    /** ID sinh viên. */
    private final Long studentId;

    /** Username công khai để phân biệt sinh viên. */
    private final String studentUsername;

    /** Họ tên sinh viên. */
    private final String studentFullName;

    /** Trạng thái bài thi hiện tại. */
    private final StudentExamStatus status;

    /** Slot, null nếu dữ liệu legacy chưa xếp lịch. */
    private final Integer slotOrder;

    /** Giờ bắt đầu dự kiến, không suy diễn lịch legacy. */
    private final OffsetDateTime scheduledStartAt;

    /** Giờ kết thúc dự kiến, có thể null với dữ liệu cũ. */
    private final OffsetDateTime scheduledEndAt;
}
