package com.avies.backend.service.impl;

import com.avies.backend.config.AzureSpeechSttProperties;
import com.avies.backend.exception.AppException;
import com.avies.backend.service.StreamingTranscriptionService;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in cloud test: only Azure, no Spring application/database or student recordings. */
@Tag("external-services")
@EnabledIfSystemProperty(named = "allowExternalServices", matches = "true")
class AzureSpeechStreamingLiveTest {
    @ParameterizedTest
    @CsvSource({"vi,vi-VN-HoaiMyNeural", "en,en-US-AriaNeural"})
    void receivesPartialAndFinalTranscriptFromRealAzure(String language, String voice) throws Exception {
        var settings = settings();
        assertTrue(settings.ready(), "Configure Azure STT in backend .env; never pass a key as a command argument");
        // Generate a short known fixture only for this test. Production Gemini TTS is unchanged.
        String text = language.equals("vi")
                ? "Xin chào. Đây là bài kiểm tra nhận dạng giọng nói tiếng Việt. Sinh viên trả lời câu hỏi bằng micro."
                : "Hello. This is a test of streaming speech recognition in English. The student answers the question using a microphone.";
        byte[] pcm = fixture(settings, voice, text);
        var updates = new CopyOnWriteArrayList<StreamingTranscriptionService.Update>();
        long started = System.nanoTime();
        try (var service = new AzureStreamingTranscriptionService(settings, new AzureSpeechStreamingEngine(settings));
             var session = service.open(language, updates::add)) {
            var busy = assertThrows(AppException.class, () -> service.open(language, updates::add));
            assertEquals(1812, busy.getErrorCode().getCode(), "Second session must be rejected locally");
            // Pace 100 ms PCM frames as a real microphone stream instead of uploading a whole file.
            for (int offset = 0; offset < pcm.length; offset += 3200) {
                session.write(Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + 3200)));
                Thread.sleep(100);
            }
            long eof = System.nanoTime();
            var result = session.finish().toCompletableFuture().get(20, TimeUnit.SECONDS);
            long partials = updates.stream().filter(u -> u.kind() == StreamingTranscriptionService.Kind.PARTIAL && !u.text().isBlank()).count();
            long finals = updates.stream().filter(u -> u.kind() == StreamingTranscriptionService.Kind.FINAL).count();
            assertTrue(partials > 0, "Real SDK must emit partial recognition before final completion");
            assertTrue(finals > 0 && result.hasSpeech(), "Real SDK must finalize recognized speech");
            assertEquals(finals, result.finalSegments());
            assertTrue(result.transcript().toLowerCase(java.util.Locale.ROOT)
                    .contains(language.equals("vi") ? "sinh viên" : "student"), "Transcript must match the known fixture");
            System.out.printf("AZURE_STT_LIVE language=%s partials=%d finals=%d eofToResultMs=%d elapsedMs=%d transcript=%s%n",
                    language, partials, finals, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - eof),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), result.transcript());
        }
    }

    static AzureSpeechSttProperties settings() throws Exception {
        var local = new Properties();
        if (Files.exists(Path.of(".env"))) {
            try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) { local.load(reader); }
        }
        var settings = new AzureSpeechSttProperties();
        ReflectionTestUtils.setField(settings, "enabled", Boolean.parseBoolean(value(local, "ORAL_AZURE_STT_ENABLED", "false")));
        ReflectionTestUtils.setField(settings, "apiKey", value(local, "AZURE_SPEECH_API_KEY", ""));
        ReflectionTestUtils.setField(settings, "region", value(local, "AZURE_SPEECH_REGION", "southeastasia"));
        return settings;
    }

    private static String value(Properties local, String name, String fallback) {
        String environment = System.getenv(name);
        return environment == null ? local.getProperty(name, fallback).strip() : environment.strip();
    }

    static byte[] fixture(AzureSpeechSttProperties settings, String voice, String text) {
        try (var config = SpeechConfig.fromSubscription(settings.getApiKey(), settings.getRegion())) {
            config.setSpeechSynthesisVoiceName(voice);
            config.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Raw16Khz16BitMonoPcm);
            try (var synthesis = new SpeechSynthesizer(config, (AudioConfig) null);
                 var result = synthesis.SpeakTextAsync(text).get(20, TimeUnit.SECONDS)) {
                assertEquals(ResultReason.SynthesizingAudioCompleted, result.getReason(), "Azure test fixture generation failed");
                return result.getAudioData();
            }
        } catch (Exception | LinkageError problem) {
            // No raw SDK exception/errorDetails: those may contain authentication data.
            throw new AssertionError("Cannot generate Azure audio fixture; check SDK/runtime, region and free quota");
        }
    }
}
