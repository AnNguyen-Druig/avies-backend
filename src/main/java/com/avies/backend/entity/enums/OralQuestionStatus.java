package com.avies.backend.entity.enums;

/** Kết quả thực hiện câu chính; không phải điểm chấm. */
public enum OralQuestionStatus {
    /** Chưa đến lượt. */ PENDING,
    /** Câu hiện tại. */ ACTIVE,
    /** Tạm bỏ qua để quay lại sau lượt đầu. */ DEFERRED,
    /** Có transcript trả lời chính đã lưu. */ ANSWERED,
    /** Không có câu trả lời; nguyên nhân được lưu riêng. */ NO_ANSWER,
    /** Chưa hoàn tất do lỗi kỹ thuật, không tự chấm không điểm. */ TECHNICAL_ERROR
}
