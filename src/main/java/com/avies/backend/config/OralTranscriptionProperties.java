package com.avies.backend.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** STT cấu hình riêng, không dùng key Gemini hoặc gọi mạng khi khởi động. */
@Getter @Component
public class OralTranscriptionProperties {
    /** Bật rõ ràng sau khi có key. */ @Value("${ORAL_STT_ENABLED:false}") private boolean enabled;
    /** Chỉ BE giữ key Groq. */ @Value("${GROQ_API_KEY:}") private String apiKey;
    /** Model đa ngôn ngữ ưu tiên tốc độ. */ @Value("${GROQ_STT_MODEL:whisper-large-v3-turbo}") private String model;
    /** Thư mục riêng, không có đường tải HTTP public. */ @Value("${ORAL_AUDIO_DIRECTORY:private/oral-audio}") private String directory;
    /** Chỉ kiểm tra đã cấu hình, không khẳng định key/quota hợp lệ. */
    public boolean ready() { return enabled && apiKey != null && !apiKey.isBlank(); }
}
