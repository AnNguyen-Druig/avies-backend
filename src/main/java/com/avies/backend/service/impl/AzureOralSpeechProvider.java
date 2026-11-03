package com.avies.backend.service.impl;

import com.avies.backend.config.AzureSpeechTtsProperties;
import com.avies.backend.exception.*;
import com.avies.backend.service.support.OralPcm;
import com.avies.backend.service.support.AzureSpeechSsml;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Azure F0 backup cùng định dạng PCM 24kHz; không đổi STT quota hoặc pricing tier. */
@Component @RequiredArgsConstructor
public class AzureOralSpeechProvider {
    private final AzureSpeechTtsProperties settings;
    private final Semaphore capacity = new Semaphore(2);
    public boolean ready() { return settings.ready(); }
    public byte[] synthesize(String text, String language) {
        var pcm = new ByteArrayOutputStream(); stream(text, language, pcm::writeBytes); return OralPcm.wav(pcm.toByteArray(), 24000);
    }
    public void stream(String text, String language, Consumer<byte[]> sink) {
        if (!ready()) throw new AppException(ErrorCode.ORAL_SPEECH_NOT_READY);
        if (text == null || text.isBlank() || text.length() > 11000 || !("vi".equals(language) || "en".equals(language))) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
        String voice = "vi".equals(language) ? settings.getVietnameseVoice() : settings.getEnglishVoice();
        String ssml = AzureSpeechSsml.render(text, language, voice);
        if (!capacity.tryAcquire()) throw new AppException(ErrorCode.ORAL_SPEECH_QUOTA);
        try (var config = SpeechConfig.fromSubscription(settings.getApiKey(), settings.getRegion())) {
            config.setSpeechSynthesisVoiceName(voice);
            config.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm);
            try (var synthesis = new SpeechSynthesizer(config, (AudioConfig)null)) {
                var delivered = new ByteArrayOutputStream(); var failure = new AtomicReference<RuntimeException>();
                var guard = new Object(); var accepting = new boolean[]{true};
                synthesis.Synthesizing.addEventListener((sender, event) -> {
                    synchronized (guard) {
                        if (!accepting[0] || failure.get() != null) return;
                        byte[] pcm = event.getResult().getAudioData(); if (pcm == null || pcm.length == 0) return;
                        try { append(delivered, pcm, sink); } catch (RuntimeException bad) { failure.set(bad); }
                    }
                });
                try (var result = synthesis.SpeakSsmlAsync(ssml).get(35, TimeUnit.SECONDS)) {
                    synchronized (guard) {
                        accepting[0] = false;
                        if (failure.get() != null) throw failure.get();
                        if (result.getReason() != ResultReason.SynthesizingAudioCompleted) {
                            var cancellation = SpeechSynthesisCancellationDetails.fromResult(result);
                            if (cancellation.getErrorCode() == CancellationErrorCode.TooManyRequests) throw new AppException(ErrorCode.ORAL_SPEECH_QUOTA);
                            if (cancellation.getErrorCode() == CancellationErrorCode.AuthenticationFailure || cancellation.getErrorCode() == CancellationErrorCode.Forbidden)
                                throw new AppException(ErrorCode.ORAL_SPEECH_NOT_READY);
                            throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
                        }
                        byte[] all = result.getAudioData(), sent = delivered.toByteArray();
                        if (all == null || all.length < 2 || all.length % 2 != 0 || all.length + 44 > GeminiOralSpeechProvider.MAX_AUDIO_BYTES
                                || sent.length > all.length || !Arrays.equals(sent, 0, sent.length, all, 0, sent.length)) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
                        // Future có thể đến sát callback cuối; bổ sung đúng phần thiếu từ kết quả SDK, không phát lặp.
                        if (sent.length < all.length) append(delivered, Arrays.copyOfRange(all, sent.length, all.length), sink);
                    }
                } finally { synchronized (guard) { accepting[0] = false; } }
            }
        } catch (AppException safe) { throw safe; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); }
        catch (Exception | LinkageError failure) { throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); }
        finally { capacity.release(); }
    }
    private static void append(ByteArrayOutputStream delivered, byte[] pcm, Consumer<byte[]> sink) {
        if (pcm.length % 2 != 0 || delivered.size() + pcm.length + 44 > GeminiOralSpeechProvider.MAX_AUDIO_BYTES) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
        delivered.writeBytes(pcm);
        for (int offset = 0; offset < pcm.length; offset += 8192) sink.accept(Arrays.copyOfRange(pcm, offset, Math.min(offset + 8192, pcm.length)));
    }
}
