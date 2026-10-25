package com.avies.backend.dto.projection;

import java.time.OffsetDateTime;

/** Bằng chứng câu trả lời đã lưu; chỉ dùng nội bộ để quyết định Finish. */
public record ExamAnswerEvidenceRow(
        /** Câu thuộc lượt thi đang khóa. */ Long studentExamQuestionId,
        /** Nội dung do Feature 3 đã lưu, không nhận từ lệnh Finish. */ String content,
        /** Thời điểm lưu do máy chủ ghi. */ OffsetDateTime createdAt) { }
