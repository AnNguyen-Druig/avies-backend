package com.avies.backend.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Không có toString chứa key, không có thao tác thay subscription/pricing tier. */
@Getter @Component
public class AzureSpeechSttProperties {
    @Value("${ORAL_AZURE_STT_ENABLED:false}") private boolean enabled;
    @Value("${AZURE_SPEECH_API_KEY:}") private String apiKey;
    @Value("${AZURE_SPEECH_REGION:southeastasia}") private String region;

    public boolean ready() {
        return enabled && apiKey != null && !apiKey.isBlank() && region != null && region.matches("[a-z][a-z0-9]{1,39}");
    }
}
