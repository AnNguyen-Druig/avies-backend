package com.avies.backend.dto.response;

/** Khả năng thực sự của backend, không suy đoán chế độ demo từ biến môi trường frontend. */
public record OralExamRuntimeResponse(
        /** LIVE: pipeline đã cấu hình; FOUNDATION: chưa đủ; LOCAL_DEMO: chỉ ở chương trình test cục bộ. */ String mode,
        /** Chỉ cho mở lượt mới khi pipeline có thể xử lý. */ boolean providersReady,
        /** Thông báo an toàn, không chứa URL/key cấu hình. */ String message,
        /** TTS đã cấu hình; độc lập với STT/AI chưa hoàn tất. */ boolean speechReady,
        /** Có cấu hình STT để kiểm tra mic/nhận dạng, chưa phải AI follow-up. */ boolean transcriptionReady,
        /** Migration metadata audio đã sẵn sàng cho upload thật. */ boolean audioStorageReady) {
    /** Giữ tương thích code bước TTS trước đó. */
    public OralExamRuntimeResponse(String mode, boolean providersReady, String message, boolean speechReady) { this(mode, providersReady, message, speechReady, false, false); }
    /** Giữ tương thích chương trình demo nền móng chưa có audio. */
    public OralExamRuntimeResponse(String mode, boolean providersReady, String message) { this(mode, providersReady, message, false); }
}
