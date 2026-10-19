package com.avies.backend.dto.response;

import lombok.Builder;
import lombok.Getter;

/** Kết quả nội bộ cho Start Exam tương lai; không có nội dung, đáp án tham khảo hoặc rubric. */
@Getter
@Builder
public class SelectedMainQuestion {
    /** ID liên kết đã được lưu trong student_exam_questions. */
    private final Long studentExamQuestionId;
    /** ID câu hỏi ngân hàng, không phải dữ liệu có thể gửi thẳng tới student UI. */
    private final Long questionId;
    /** Thứ tự chính thức, bắt đầu từ 1 và không thay đổi khi gọi lại. */
    private final Integer questionOrder;
}
