package com.avies.backend.dto.response;

import com.avies.backend.entity.enums.ExamSessionStatus;
import com.avies.backend.entity.enums.StudentExamStatus;
import java.time.OffsetDateTime;

/** Kết quả chuyển trạng thái; không tiết lộ đáp án, rubric, điểm hoặc thông tin sinh viên khác. */
public record ExamAttemptResponse(
        /** ID lượt thi đã kiểm tra quyền. */ Long id,
        /** ID phiên chứa lượt thi. */ Long sessionId,
        /** Trạng thái lượt thi sau thao tác. */ StudentExamStatus status,
        /** Trạng thái phiên sau thao tác. */ ExamSessionStatus sessionStatus,
        /** Giờ bắt đầu thực tế do máy chủ ghi. */ OffsetDateTime startedAt,
        /** Giờ hoàn thành thực tế; vắng/hết giờ giữ null. */ OffsetDateTime completedAt,
        /** Hạn nộp cố định bằng giờ kết thúc slot, không cộng giờ khi vào muộn. */ OffsetDateTime deadlineAt) { }
