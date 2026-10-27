package com.avies.backend.dto.snapshot;

import java.time.OffsetDateTime;
import java.util.List;

/** Dữ liệu chấm nội bộ chỉ đọc snapshot, không tham chiếu nội dung ngân hàng hiện hành. */
public record ExamGradingContext(
        /** Lượt thi được đọc. */ Long studentExamId,
        /** Phiên sở hữu rubric dùng chung. */ Long examSessionId,
        /** Thang chấm đã chốt. */ ExamRubricSnapshot rubric,
        /** Thời điểm chốt rubric. */ OffsetDateTime rubricCapturedAt,
        /** Bộ câu đầy đủ theo thứ tự thi. */ List<ExamQuestionSnapshot> questions) {
    /** Không cho caller thêm, xóa hoặc đổi thứ tự câu trong kết quả. */
    public ExamGradingContext { questions = List.copyOf(questions); }
}
