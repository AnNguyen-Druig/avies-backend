package com.avies.backend.service.impl;

import com.avies.backend.config.AzureSpeechTtsProperties;
import com.avies.backend.exception.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Force a Gemini 429 locally; backup synthesis uses the real F0 resource, no Google quota consumed. */
@Tag("external-services") @EnabledIfSystemProperty(named="allowExternalServices",matches="true")
class AzureTtsBackupLiveTest {
    @ParameterizedTest @ValueSource(strings={"vi","en"})
    void realAzureBackupStreamsWhenGeminiHasNoQuota(String language) throws Exception {
        var local = new Properties(); try (var reader = Files.newBufferedReader(Path.of(".env"),StandardCharsets.UTF_8)) { local.load(reader); }
        var settings = new AzureSpeechTtsProperties();
        ReflectionTestUtils.setField(settings,"enabled",Boolean.parseBoolean(value(local,"AZURE_TTS_BACKUP_ENABLED",value(local,"ORAL_AZURE_STT_ENABLED","false"))));
        ReflectionTestUtils.setField(settings,"apiKey",value(local,"AZURE_TTS_API_KEY",value(local,"AZURE_SPEECH_API_KEY","")));
        ReflectionTestUtils.setField(settings,"region",value(local,"AZURE_TTS_REGION",value(local,"AZURE_SPEECH_REGION","southeastasia")));
        ReflectionTestUtils.setField(settings,"vietnameseVoice",value(local,"AZURE_TTS_VOICE_VI","en-US-AvaMultilingualNeural"));
        ReflectionTestUtils.setField(settings,"englishVoice",value(local,"AZURE_TTS_VOICE_EN","en-US-AvaMultilingualNeural"));
        assertTrue(settings.ready(),"Configure local Azure Speech F0 before the opt-in backup test");
        var primary = mock(GeminiOralSpeechProvider.class); doThrow(new AppException(ErrorCode.ORAL_SPEECH_QUOTA)).when(primary).stream(anyString(),anyString(),any());
        try (var azure = new AzureOralSpeechProvider(settings)) {
        var backup = new FailoverOralSpeechProvider(primary,azure,Clock.systemUTC());
        var chunks = new ArrayList<byte[]>(); long started = System.nanoTime(); long[] first = {0};
        backup.stream(language.equals("vi") ? "Câu số một. Bạn hãy nêu lí do tại sao phải đặt tên các use case khác nhau?" : "Question number one. Please explain your answer.", language, pcm -> {
            if (chunks.isEmpty()) first[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started); chunks.add(pcm);
        });
        assertTrue(chunks.size()>1); assertTrue(chunks.stream().allMatch(pcm -> pcm.length>0&&pcm.length%2==0&&pcm.length<=8192));
        int bytes = chunks.stream().mapToInt(pcm->pcm.length).sum(); assertTrue(bytes>48000);
        var pcm = new java.io.ByteArrayOutputStream(); chunks.forEach(pcm::writeBytes);
        Path previews = Path.of("target", "tts-preview"); Files.createDirectories(previews);
        Files.write(previews.resolve("azure-multilingual-" + language + ".wav"), com.avies.backend.service.support.OralPcm.wav(pcm.toByteArray(),24000));
        verify(primary,times(1)).stream(anyString(),anyString(),any());
        System.out.printf("AZURE_TTS_BACKUP_LIVE language=%s chunks=%d bytes=%d firstAudioMs=%d totalMs=%d%n",language,chunks.size(),bytes,first[0],TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        }
    }
    private static String value(Properties local,String key,String fallback) { return System.getenv(key)==null?local.getProperty(key,fallback).strip():System.getenv(key).strip(); }
}
