package com.avies.backend.dto.snapshot;

import java.time.OffsetDateTime;

/** Câu đã chốt cho luồng thi/chấm nội bộ; không được serialize trực tiếp cho sinh viên vì có đáp án. */
public record ExamQuestionSnapshot(
        /** ID liên kết ổn định. */ Long studentExamQuestionId,
        /** ID câu nguồn để truy xuất. */ Long sourceQuestionId,
        /** Thứ tự chính thức trong lượt thi. */ Integer questionOrder,
        /** Version câu khi chốt. */ Long sourceVersion,
        /** Nội dung đề thi lịch sử. */ String content,
        /** Đáp án tham khảo lịch sử; null được giữ nguyên. */ String referenceAnswer,
        /** Bloom lịch sử. */ String bloomLevel,
        /** Giới hạn riêng của câu, có thể null. */ Integer answerTimeLimitSeconds,
        /** Thời điểm chốt. */ OffsetDateTime capturedAt) { }
