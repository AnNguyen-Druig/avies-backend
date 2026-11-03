package com.avies.backend.service.impl;

import com.avies.backend.config.OralSpeechProperties;
import com.avies.backend.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Tag("external-services") @EnabledIfSystemProperty(named="allowExternalServices",matches="true")
class GeminiSpeechStreamingLiveTest {
    @Test void receivesRealGeminiPcmChunksOrExplicitlyReportsQuotaBlock() throws Exception {
        var values = new Properties(); try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) { values.load(reader); }
        var settings = new OralSpeechProperties();
        ReflectionTestUtils.setField(settings,"enabled",Boolean.parseBoolean(value(values,"ORAL_TTS_ENABLED","false")));
        ReflectionTestUtils.setField(settings,"apiKey",value(values,"GEMINI_TTS_API_KEY",""));
        ReflectionTestUtils.setField(settings,"model",value(values,"GEMINI_TTS_MODEL","gemini-3.8-flash-tts"));
        ReflectionTestUtils.setField(settings,"voice",value(values,"GEMINI_TTS_VOICE","Kore"));
        assertTrue(settings.ready(),"Configure local Gemini TTS settings before this opt-in test");
        long started = System.nanoTime(); var first = new AtomicLong(); var bytes = new AtomicInteger(); var chunks = new AtomicInteger();
        try {
            new GeminiOralSpeechProvider(settings).stream("Question number one. Please explain your answer and give one example.","en",pcm -> {
                first.compareAndSet(0,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)); chunks.incrementAndGet(); bytes.addAndGet(pcm.length);
            });
        } catch (AppException safe) {
            if (safe.getErrorCode() == ErrorCode.ORAL_SPEECH_QUOTA) {
                System.out.println("GEMINI_TTS_STREAM_LIVE_NOT_VERIFIED code=1809 reason=QUOTA");
                Assumptions.abort("Gemini TTS quota exhausted; live streaming performance remains unverified");
            }
            throw safe;
        }
        assertTrue(chunks.get()>0 && bytes.get()>0);
        System.out.printf("GEMINI_TTS_STREAM_LIVE chunks=%d bytes=%d firstAudioMs=%d totalMs=%d%n",chunks.get(),bytes.get(),first.get(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
    }
    private static String value(Properties local,String key,String fallback) { return System.getenv(key)==null?local.getProperty(key,fallback).strip():System.getenv(key).strip(); }
}
