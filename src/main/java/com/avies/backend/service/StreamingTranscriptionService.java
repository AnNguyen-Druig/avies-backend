package com.avies.backend.service;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Biên STT nội bộ cho transport audio; caller phải kiểm tra owner/turn/lease trước khi mở. */
public interface StreamingTranscriptionService {
    /** Chỉ vi/en; không nhận prompt, đáp án hoặc rubric. Partial không phải bằng chứng đã nộp. */
    // Consumer chạy trên callback SDK, phải trả về nhanh và không block chờ finish().
    Session open(String language, Consumer<Update> updates);

    enum Kind { PARTIAL, FINAL }
    /** FINAL là một đoạn Azure đã chốt, chưa phải toàn bộ câu trả lời đã nộp. */
    record Update(Kind kind, int segmentIndex, String text) { }
    /** Chỉ hoàn tất sau EOF/sessionStopped; cleanup SDK chạy riêng, vẫn giữ giới hạn STT đến khi đóng xong. */
    record Result(String transcript, int finalSegments) { public boolean hasSpeech() { return !transcript.isBlank(); } }

    interface Session extends AutoCloseable {
        /** PCM signed 16-bit little-endian, mono 16 kHz, không có WAV/container header. */
        void write(byte[] pcm);
        /** Đóng đầu vào và đợi tất cả final; gọi lặp trả cùng future. */
        CompletionStage<Result> finish();
        /** Disconnect/cancel không tự nộp partial hoặc biến lỗi thành transcript rỗng. */
        @Override void close();
    }
}
