package com.avies.backend.service.impl;

import com.avies.backend.exception.*;
import com.avies.backend.service.OralSpeechProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Gemini chính, Azure dự phòng; không đổi giọng giữa một câu đã phát dở. */
@Service @Primary @RequiredArgsConstructor @lombok.extern.slf4j.Slf4j
public class FailoverOralSpeechProvider implements OralSpeechProvider {
    private final GeminiOralSpeechProvider gemini;
    private final AzureOralSpeechProvider azure;
    private final Clock clock;
    private volatile Instant retryGeminiAt = Instant.EPOCH;
    @Override public byte[] synthesize(String text, String language) {
        return call(() -> gemini.synthesize(text, language), () -> azure.synthesize(text, language), () -> false);
    }
    @Override public void stream(String text, String language, Consumer<byte[]> sink) {
        var began = new AtomicBoolean();
        call(() -> { gemini.stream(text, language, pcm -> { began.set(true); sink.accept(pcm); }); return null; },
                () -> { azure.stream(text, language, sink); return null; }, began::get);
    }
    private <T> T call(Supplier<T> primary, Supplier<T> backup, BooleanSupplier began) {
        if (azure.ready() && clock.instant().isBefore(retryGeminiAt)) return backup.get();
        try { return primary.get(); }
        catch (AppException failure) {
            int code = failure.getErrorCode().getCode();
            if (!Set.of(1807,1808,1809).contains(code) || !azure.ready()) throw failure;
            retryGeminiAt = clock.instant().plusSeconds(code == 1808 ? 30 : 300);
            if (began.getAsBoolean()) throw failure;
            log.info("oral-tts fallback=AZURE reasonCode={} primaryCooldownSeconds={}", code, code == 1808 ? 30 : 300);
            return backup.get();
        }
    }
}
