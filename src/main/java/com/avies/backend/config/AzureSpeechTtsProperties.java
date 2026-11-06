package com.avies.backend.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Dùng resource/key hiện có nếu không khai báo TTS riêng; không có API quản
 * trị/billing.
 */
@Getter
@Component
public class AzureSpeechTtsProperties {
    @Value("${AZURE_TTS_BACKUP_ENABLED:${ORAL_AZURE_STT_ENABLED:false}}")
    private boolean enabled;
    @Value("${AZURE_TTS_API_KEY:${AZURE_SPEECH_API_KEY:}}")
    private String apiKey;
    @Value("${AZURE_TTS_REGION:${AZURE_SPEECH_REGION:southeastasia}}")
    private String region;
    @Value("${AZURE_TTS_VOICE_VI:en-US-AvaMultilingualNeural}")
    private String vietnameseVoice;
    @Value("${AZURE_TTS_VOICE_EN:en-US-AvaMultilingualNeural}")
    private String englishVoice;

    public boolean ready() {
        return enabled && apiKey != null && !apiKey.isBlank() && region != null && region.matches("[a-z][a-z0-9]{1,39}")
                && validVoice(vietnameseVoice, "vi-VN-") && validVoice(englishVoice, "en-US-");
    }

    private static boolean validVoice(String voice, String prefix) {
        return multilingual(voice)
                || voice != null && voice.startsWith(prefix) && voice.matches("[a-z]{2}-[A-Z]{2}-[A-Za-z0-9]+Neural");
    }

    /**
     * Chỉ các giọng multilingual đã hỗ trợ cả vi-VN/en-US; không suy đoán từ một
     * tên voice bất kỳ.
     */
    public static boolean multilingual(String voice) {
        return voice != null && java.util.Set.of("en-US-AvaMultilingualNeural", "en-US-EmmaMultilingualNeural",
                "en-US-AndrewMultilingualNeural", "en-US-BrianMultilingualNeural").contains(voice);
    }
}
