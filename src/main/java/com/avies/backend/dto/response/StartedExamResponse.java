package com.avies.backend.dto.response;

import java.util.List;

/** Đề thi chỉ lấy từ snapshot; tuyệt đối không serialize đối tượng snapshot chấm điểm nội bộ. */
public record StartedExamResponse(
        /** Trạng thái và hạn làm bài. */ ExamAttemptResponse attempt,
        /** Các câu chính theo thứ tự đã chốt. */ List<Question> questions) {
    /** Nội dung tối thiểu sinh viên được xem; không có đáp án tham khảo hoặc rubric. */
    public record Question(
            /** ID liên kết để Feature 3 lưu lượt hội thoại đúng câu. */ Long studentExamQuestionId,
            /** Thứ tự chính thức trong lượt thi. */ Integer questionOrder,
            /** Nội dung câu tại thời điểm chốt. */ String content,
            /** Giới hạn của câu nếu có, không thay hạn cuối của slot. */ Integer answerTimeLimitSeconds) { }
}
