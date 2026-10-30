package com.avies.backend.service;

import com.avies.backend.service.support.OralAudioPayload;

/** Biên STT chỉ nhận audio/ngôn ngữ, không biết rubric, đáp án hoặc quyền Student. */
public interface OralTranscriptionProvider {
    /** Transcript rỗng chỉ khi metadata cho thấy không có lời nói; lỗi phải ném exception riêng. */
    String transcribe(OralAudioPayload audio, String language);
}
