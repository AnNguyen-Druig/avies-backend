package com.avies.backend.dto.response;

import lombok.Builder;
import lombok.Getter;
import java.time.OffsetDateTime;
import com.avies.backend.entity.enums.StudentExamStatus;
import com.avies.backend.entity.enums.ExamSessionStatus;

/** Lịch thi cá nhân; tuyệt đối không bao gồm ngân hàng câu hỏi, đáp án, rubric hoặc điểm. */
@Getter
@Builder
public class MyStudentExamResponse {
    /** ID bài thi của chính sinh viên. */
    private final Long id;

    /** ID phiên chứa bài thi. */
    private final Long sessionId;

    /** Tiêu đề phiên thi. */
    private final String sessionTitle;

    /** ID môn. */
    private final Long subjectId;

    /** Mã môn. */
    private final String subjectCode;

    /** Tên môn. */
    private final String subjectName;

    /** Trạng thái phiên. */
    private final ExamSessionStatus sessionStatus;

    /** Trạng thái bài thi. */
    private final StudentExamStatus status;

    /** Thứ tự slot; null nếu chưa xếp lịch. */
    private final Integer slotOrder;

    /** Bắt đầu dự kiến; không thay bằng startedAt. */
    private final OffsetDateTime scheduledStartAt;

    /** Kết thúc dự kiến; không suy diễn lịch thiếu. */
    private final OffsetDateTime scheduledEndAt;
}
