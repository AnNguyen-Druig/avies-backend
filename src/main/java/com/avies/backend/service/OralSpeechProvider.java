package com.avies.backend.service;

/** Biên nhà cung cấp; không biết database, token Student hoặc đáp án/rubric. */
public interface OralSpeechProvider {
    /** Chỉ trả WAV PCM đã kiểm tra, lỗi không chứa response/key nhà cung cấp. */
    byte[] synthesize(String text, String language);
    /** PCM mono 24kHz s16le; default giữ tương thích các provider test cũ. */
    default void stream(String text, String language, java.util.function.Consumer<byte[]> chunk) {
        chunk.accept(com.avies.backend.service.support.OralPcm.fromWav(synthesize(text, language)));
    }
}
