package com.avies.backend.service.impl;

import com.avies.backend.config.AzureSpeechSttProperties;
import com.avies.backend.exception.*;
import com.avies.backend.service.support.StreamingRecognitionEngine;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/** SDK chính thức qua region; không sử dụng endpoint REST quản trị resource hoặc đổi gói F0. */
@Component @RequiredArgsConstructor
public class AzureSpeechStreamingEngine implements StreamingRecognitionEngine {
    private final AzureSpeechSttProperties settings;

    @Override public Channel open(String language, Listener listener) {
        if (!settings.ready() || !("vi".equals(language) || "en".equals(language)))
            throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_NOT_READY);
        SpeechConfig config = null; AudioStreamFormat format = null; PushAudioInputStream input = null;
        AudioConfig audio = null; SpeechRecognizer recognizer = null;
        try {
            config = SpeechConfig.fromSubscription(settings.getApiKey(), settings.getRegion());
            config.setSpeechRecognitionLanguage("vi".equals(language) ? "vi-VN" : "en-US");
            format = AudioStreamFormat.getWaveFormatPCM(16000, (short)16, (short)1);
            input = AudioInputStream.createPushStream(format); audio = AudioConfig.fromStreamInput(input);
            recognizer = new SpeechRecognizer(config, audio);
            recognizer.recognizing.addEventListener((sender, event) -> listener.partial(event.getResult().getText()));
            recognizer.recognized.addEventListener((sender, event) -> {
                if (event.getResult().getReason() == ResultReason.RecognizedSpeech)
                    listener.recognized(event.getResult().getResultId(), event.getResult().getText());
                else if (event.getResult().getReason() == ResultReason.NoMatch) listener.partial("");
            });
            recognizer.canceled.addEventListener((sender, event) -> {
                if (event.getReason() == CancellationReason.Error) listener.failed(safeCode(event.getErrorCode()));
                // EndOfStream không tự kết thúc trước khi nhận sessionStopped và các final đang chờ.
            });
            recognizer.sessionStopped.addEventListener((sender, event) -> listener.stopped());
            recognizer.startContinuousRecognitionAsync().get(10, TimeUnit.SECONDS);
            return new NativeChannel(config, format, input, audio, recognizer);
        } catch (Exception | LinkageError failure) {
            closeQuietly(recognizer); closeQuietly(audio); closeQuietly(input); closeFormat(format); closeQuietly(config);
            // Không log getMessage/errorDetails vì SDK/provider có thể kèm dữ liệu xác thực.
            throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_NOT_READY);
        }
    }

    static int safeCode(CancellationErrorCode code) {
        if (code == CancellationErrorCode.AuthenticationFailure || code == CancellationErrorCode.Forbidden) return 1810;
        if (code == CancellationErrorCode.TooManyRequests) return 1812;
        if (code == CancellationErrorCode.BadRequest) return 1813;
        return 1811;
    }

    private record NativeChannel(SpeechConfig config, AudioStreamFormat format, PushAudioInputStream input,
                                 AudioConfig audio, SpeechRecognizer recognizer) implements Channel {
        @Override public void write(byte[] pcm) { input.write(pcm); }
        @Override public void endInput() { input.close(); }
        @Override public void close() {
            try { recognizer.stopContinuousRecognitionAsync().get(10, TimeUnit.SECONDS); }
            catch (Exception ignored) { /* dispose bên dưới vẫn phải hoàn tất trước khi nhả giới hạn. */ }
            finally {
                try { recognizer.close(); }
                finally { closeQuietly(audio); closeQuietly(input); closeFormat(format); closeQuietly(config); }
            }
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) try { resource.close(); } catch (Exception ignored) { }
    }
    private static void closeFormat(AudioStreamFormat format) {
        if (format != null) try { format.close(); } catch (Exception ignored) { }
    }
}
