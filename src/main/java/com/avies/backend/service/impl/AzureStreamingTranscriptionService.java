package com.avies.backend.service.impl;

import com.avies.backend.config.AzureSpeechSttProperties;
import com.avies.backend.exception.*;
import com.avies.backend.service.StreamingTranscriptionService;
import com.avies.backend.service.support.StreamingRecognitionEngine;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Một singleton/instance BE cho demo F0. Không tự retry quota, không chuyển gói hoặc lưu partial vào bài thi. */
@Service @Slf4j
public class AzureStreamingTranscriptionService implements StreamingTranscriptionService, AutoCloseable {
    static final long MAX_PCM_BYTES = 16000L * 2 * 300;
    private final AzureSpeechSttProperties settings;
    private final StreamingRecognitionEngine engine;
    private final AtomicReference<Active> active = new AtomicReference<>();
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor(task -> daemon(task, "azure-stt-cleanup"));
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "azure-stt-watchdog"));
    private volatile boolean shutdown;

    public AzureStreamingTranscriptionService(AzureSpeechSttProperties settings, StreamingRecognitionEngine engine) {
        this.settings = settings; this.engine = engine;
        watchdog.scheduleWithFixedDelay(() -> checkTimeouts(System.nanoTime()), 1, 1, TimeUnit.SECONDS);
    }

    @Override public synchronized Session open(String language, Consumer<Update> updates) {
        if (shutdown || !settings.ready()) throw failure(1810);
        if (!Set.of("vi", "en").contains(Objects.toString(language, "")) || updates == null) throw failure(1813);
        var session = new Active(updates);
        if (!active.compareAndSet(null, session)) throw failure(1812);
        try { session.bind(engine.open(language, session)); return session; }
        catch (RuntimeException | LinkageError failed) {
            session.openFailed(failed instanceof AppException safe ? safe.getErrorCode().getCode() : 1810);
            throw failure(failed instanceof AppException safe ? safe.getErrorCode().getCode() : 1810);
        }
    }

    /** Timeout không được biến bản ghi chưa hoàn chỉnh thành câu trả lời rỗng/thành công. */
    void checkTimeouts(long now) {
        var session = active.get(); if (session == null) return;
        synchronized (session) {
            if (session.closing) return;
            if (session.finishing ? now - session.finishAt > TimeUnit.SECONDS.toNanos(15)
                    : now - session.lastAudio > TimeUnit.SECONDS.toNanos(10) || now - session.started > TimeUnit.SECONDS.toNanos(305))
                session.failed(1811);
        }
    }

    @Override @PreDestroy public synchronized void close() {
        shutdown = true; watchdog.shutdownNow();
        var session = active.get(); if (session != null) session.close();
        cleanup.shutdown();
        try { if (!cleanup.awaitTermination(15, TimeUnit.SECONDS)) cleanup.shutdownNow(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); cleanup.shutdownNow(); }
    }

    private static Thread daemon(Runnable task, String name) { var thread = new Thread(task, name); thread.setDaemon(true); return thread; }
    private static AppException failure(int code) {
        return new AppException(switch (code) {
            case 1810 -> ErrorCode.ORAL_TRANSCRIPTION_NOT_READY;
            case 1812 -> ErrorCode.ORAL_TRANSCRIPTION_QUOTA;
            case 1813 -> ErrorCode.ORAL_AUDIO_INVALID;
            default -> ErrorCode.ORAL_TRANSCRIPTION_FAILED;
        });
    }

    private final class Active implements Session, StreamingRecognitionEngine.Listener {
        final Consumer<Update> updates;
        final LinkedHashMap<String, String> segments = new LinkedHashMap<>();
        final CompletableFuture<Result> completion = new CompletableFuture<>();
        final CompletionStage<Result> result = completion.minimalCompletionStage();
        final long started = System.nanoTime();
        long lastAudio = started, finishAt, bytes;
        boolean opening = true, finishing, closing, cleanupQueued, partialOutstanding;
        StreamingRecognitionEngine.Channel channel;
        Integer error;
        Result completed;

        Active(Consumer<Update> updates) { this.updates = updates; }

        synchronized void bind(StreamingRecognitionEngine.Channel value) {
            channel = Objects.requireNonNull(value); opening = false; if (closing) dispose();
        }

        synchronized void openFailed(int code) { opening = false; if (closing) dispose(); else failed(code); }

        @Override public synchronized void write(byte[] pcm) {
            if (closing || finishing) throw failure(error == null ? 1811 : error);
            if (pcm == null || pcm.length == 0 || pcm.length % 2 != 0 || pcm.length > 64000 || bytes + pcm.length > MAX_PCM_BYTES
                    || bytes == 0 && pcm.length >= 4 && pcm[0] == 'R' && pcm[1] == 'I' && pcm[2] == 'F' && pcm[3] == 'F') {
                failed(1813); throw failure(1813);
            }
            bytes += pcm.length; lastAudio = System.nanoTime();
            try { channel.write(pcm); }
            catch (RuntimeException provider) { failed(1811); throw failure(1811); }
        }

        @Override public synchronized CompletionStage<Result> finish() {
            if (closing || finishing) return result;
            if (bytes == 0) { failed(1813); return result; }
            finishing = true; finishAt = System.nanoTime();
            try { channel.endInput(); } // EOF trước; không Stop ngay vì có thể cắt mất phần audio chưa nhận dạng.
            catch (RuntimeException provider) { failed(1811); }
            return result;
        }

        @Override public synchronized void partial(String text) {
            if (closing) return;
            if (!validText(text)) { failed(1811); return; }
            String value = text.strip(); partialOutstanding = !value.isEmpty();
            emit(new Update(Kind.PARTIAL, segments.size() + 1, value));
        }

        @Override public synchronized void recognized(String id, String text) {
            if (closing) return;
            if (id == null || id.isBlank() || !validText(text) || text.isBlank() || segments.size() >= 1000) { failed(1811); return; }
            String value = text.strip(), previous = segments.get(id);
            if (previous != null) { if (!previous.equals(value)) failed(1811); return; }
            if (String.join(" ", segments.values()).length() + value.length() + 1 > 20000) { failed(1811); return; }
            segments.put(id, value); partialOutstanding = false;
            emit(new Update(Kind.FINAL, segments.size(), value));
        }

        private boolean validText(String text) { return text != null && text.length() <= 20000 && text.indexOf('\0') < 0; }
        private void emit(Update update) { try { updates.accept(update); } catch (RuntimeException disconnected) { failed(1811); } }

        @Override public synchronized void stopped() {
            if (closing) return;
            if (!finishing || partialOutstanding) { failed(1811); return; }
            completed = new Result(String.join(" ", segments.values()), segments.size()); closing = true; dispose();
            // sessionStopped sau EOF đã chốt mọi final. Chạy bước AI song song cleanup,
            // nhưng active chỉ được nhả sau khi native session thực sự đóng.
            completion.complete(completed);
        }

        @Override public synchronized void failed(int code) {
            if (closing) return;
            error = Set.of(1810, 1811, 1812, 1813).contains(code) ? code : 1811;
            closing = true; dispose();
        }

        /** SDK callback chỉ xếp cleanup; không close/đợi SDK trên chính callback thread của SDK. */
        private synchronized void dispose() {
            if (opening || cleanupQueued) return;
            cleanupQueued = true;
            cleanup.execute(() -> {
                long cleanupStarted = System.nanoTime();
                boolean released = false;
                try {
                    if (channel != null) channel.close();
                    released = active.compareAndSet(this, null);
                } catch (RuntimeException nativeFailure) {
                    log.warn("azure-stt cleanup=FAILED gate=CLOSED"); // Không cấp phiên thứ hai khi chưa chắc native session đã đóng.
                } finally {
                    if (completed == null || error != null) completion.completeExceptionally(failure(error == null ? 1811 : error));
                    log.info("azure-stt cleanupMs={} released={}", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-cleanupStarted), released);
                }
            });
        }

        @Override public void close() { failed(1811); }
    }
}
