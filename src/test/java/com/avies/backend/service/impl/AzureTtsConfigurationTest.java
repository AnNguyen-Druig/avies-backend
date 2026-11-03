package com.avies.backend.service.impl;

import com.avies.backend.config.AzureSpeechTtsProperties;
import com.avies.backend.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AzureTtsConfigurationTest {
    @Test void reusesExistingSpeechConfigurationAndCanBeExplicitlyDisabled() {
        for (boolean enabled : new boolean[]{true,false}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of(
                        "ORAL_AZURE_STT_ENABLED","true","AZURE_TTS_BACKUP_ENABLED",Boolean.toString(enabled),
                        "AZURE_SPEECH_API_KEY","not-a-real-key","AZURE_SPEECH_REGION","southeastasia")));
                context.register(AzureSpeechTtsProperties.class); context.refresh(); var settings = context.getBean(AzureSpeechTtsProperties.class);
                assertThat(settings.ready()).isEqualTo(enabled); assertThat(settings.getRegion()).isEqualTo("southeastasia");
                assertThat(settings.getVietnameseVoice()).isEqualTo("en-US-AvaMultilingualNeural"); assertThat(settings.getEnglishVoice()).isEqualTo("en-US-AvaMultilingualNeural");
            }
        }
    }
    @Test void disabledOrInvalidInputCannotStartNativeSynthesis() {
        var settings = mock(AzureSpeechTtsProperties.class); var provider = new AzureOralSpeechProvider(settings);
        assertThatThrownBy(() -> provider.stream("Question?","en",ignored -> {})).isInstanceOf(AppException.class);
        when(settings.ready()).thenReturn(true);
        assertThatThrownBy(() -> provider.stream("Question?","fr",ignored -> {})).isInstanceOf(AppException.class);
        verify(settings,never()).getApiKey();
    }
    @Test void permitsKnownVietnameseEnglishMultilingualVoicesAndKeepsExplicitLegacyVoices() {
        for (var voice : java.util.List.of("en-US-AvaMultilingualNeural","en-US-EmmaMultilingualNeural","vi-VN-HoaiMyNeural")) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of(
                        "AZURE_TTS_BACKUP_ENABLED","true","AZURE_SPEECH_API_KEY","not-a-real-key",
                        "AZURE_SPEECH_REGION","southeastasia","AZURE_TTS_VOICE_VI",voice)));
                context.register(AzureSpeechTtsProperties.class); context.refresh();
                assertThat(context.getBean(AzureSpeechTtsProperties.class).ready()).isTrue();
            }
        }
    }
}
