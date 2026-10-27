package com.avies.backend.exception;

/** Lỗi thiếu ngân hàng câu hỏi; chỉ mang số tối thiểu, không chứa nội dung đề hoặc SQL. */
public class ExamSessionQuestionPoolException extends AppException {
    /** Số câu chính tối thiểu theo cấu hình phiên, không nhân số sinh viên hoặc câu đào sâu. */
    private final int requiredCount;

    /** Tạo thông báo tiếng Việt từ mẫu cố định và số câu đã kiểm tra. */
    public ExamSessionQuestionPoolException(int requiredCount) {
        super(ErrorCode.EXAM_SESSION_QUESTIONS_REQUIRED,
                ErrorCode.EXAM_SESSION_QUESTIONS_REQUIRED.getMessage().formatted(requiredCount));
        this.requiredCount = requiredCount;
    }

    /** Số tối thiểu để handler dựng lại thông báo an toàn. */
    public int getRequiredCount() {
        return requiredCount;
    }
}
