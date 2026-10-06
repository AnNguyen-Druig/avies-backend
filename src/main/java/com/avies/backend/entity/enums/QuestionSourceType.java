package com.avies.backend.entity.enums;

/** Nguồn tạo câu hỏi do backend xác định, không nhận từ người dùng. */
public enum QuestionSourceType {
    /** Câu hỏi được giảng viên nhập thủ công. */
    MANUAL,
    /** Câu hỏi được nhập từ tập tin có cấu trúc. */
    IMPORT,
    /** Câu hỏi được sinh bởi dịch vụ AI. */
    AI
}
