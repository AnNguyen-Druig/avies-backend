package com.avies.backend.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Cấu hình TTS riêng, không thay model hoặc API key của Feature 1. */
@Getter
@Component
public class OralSpeechProperties {
    /** Chủ động bật sau khi cấu hình key; mặc định không gọi dịch vụ ngoài. */
    @Value("${ORAL_TTS_ENABLED:false}") private boolean enabled;
    /** Chỉ dùng key TTS riêng, không log hoặc trả về client. */
    @Value("${GEMINI_TTS_API_KEY:}") private String apiKey;
    /** Model TTS có Free Tier, thay được qua môi trường. */
    @Value("${GEMINI_TTS_MODEL:gemini-3.8-flash-tts}") private String model;
    /** Giọng dựng sẵn, không cần cài giọng trên Windows. */
    @Value("${GEMINI_TTS_VOICE:Kore}") private String voice;
    /** Chỉ báo cấu hình đã có, không cam kết key còn quota hoặc hợp lệ. */
    public boolean ready() { return enabled && apiKey != null && !apiKey.isBlank(); }
}
