package com.avies.backend.service;

/** Biên nhà cung cấp; không biết database, token Student hoặc đáp án/rubric. */
public interface OralSpeechProvider {
    /** Chỉ trả WAV PCM đã kiểm tra, lỗi không chứa response/key nhà cung cấp. */
    byte[] synthesize(String text, String language);
}
