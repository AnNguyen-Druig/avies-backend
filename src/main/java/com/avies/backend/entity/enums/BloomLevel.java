package com.avies.backend.entity.enums;

/** Các mức nhận thức được hỗ trợ trong ngân hàng câu hỏi của MVP. */
public enum BloomLevel {
    /** Ghi nhớ và nhắc lại kiến thức. */
    REMEMBER,
    /** Giải thích và diễn đạt kiến thức. */
    UNDERSTAND,
    /** Áp dụng kiến thức vào tình huống cụ thể. */
    APPLY,
    /** Phân tích cấu trúc, quan hệ và nguyên nhân. */
    ANALYZE,
    /** Đánh giá, kiểm chứng và hình thành quan điểm. */
    EVALUATE,
    /** Sáng tạo, tạo ra sản phẩm mới hoặc giải pháp độc đáo. */
    CREATE;

    @com.fasterxml.jackson.annotation.JsonCreator
    public static BloomLevel fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        for (BloomLevel level : BloomLevel.values()) {
            if (level.name().equalsIgnoreCase(value.trim())) {
                return level;
            }
        }
        throw new IllegalArgumentException("bloomLevel phải nằm trong 6 level: REMEMBER, UNDERSTAND, APPLY, ANALYZE, EVALUATE và CREATE.");
    }
}
