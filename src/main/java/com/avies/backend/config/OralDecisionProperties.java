package com.avies.backend.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Cấu hình AI hội thoại riêng, không đổi provider/model của Feature 1. */
@Getter @Component
public class OralDecisionProperties {
    /** Chỉ bật sau khi migration và cấu hình dịch vụ đã sẵn sàng. */
    @Value("${ORAL_AI_ENABLED:false}") private boolean enabled;
    /** Có thể dùng key Gemini đang có; không log hoặc gửi key ra FE. */
    @Value("${GEMINI_ORAL_API_KEY:${GEMINI_API_KEY:}}") private String apiKey;
    /** Model text có structured output và Free Tier, tách khỏi model TTS. */
    @Value("${GEMINI_ORAL_MODEL:gemini-3.5-flash-lite}") private String model;
    /** Chỉ xác nhận cấu hình, không cam kết quota hoặc quyền truy cập của key. */
    public boolean ready() { return enabled && apiKey != null && !apiKey.isBlank() && model != null && model.matches("gemini-[a-zA-Z0-9.\\-]+") && !model.endsWith("-tts"); }
}
