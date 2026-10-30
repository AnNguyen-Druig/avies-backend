package com.avies.backend.service.impl;

import com.avies.backend.config.OralTranscriptionProperties;
import com.avies.backend.dto.request.OralAudioRequest;
import com.avies.backend.dto.response.OralExamResponse;
import com.avies.backend.exception.*;
import com.avies.backend.service.*;
import com.avies.backend.service.support.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Không gọi mạng trong transaction hoặc cho Student tự ghi transcript vào bài thi. */
@Service @Validated @RequiredArgsConstructor
public class OralTranscriptionServiceImpl implements OralTranscriptionService {
    /** Bộ điều phối thật kiểm tra ownership/lease. */ private final OralExamService oral;
    /** Preview cũng kiểm tra role hiện hành trong DB. */ private final ExamAccessPolicy access;
    /** Cấu hình STT. */ private final OralTranscriptionProperties settings;
    /** Groq được thay bằng stub trong test. */ private final OralTranscriptionProvider provider;
    /** Chặn preview song song vô hạn. */ private final Semaphore previewLimit = new Semaphore(2);
    /** Audio được validate trước khi gắn vào job. */
    @Override public OralExamResponse upload(Long examId, UUID job, OralAudioRequest request, MultipartFile audio) {
        ready(); return oral.attachAudio(examId, job, request, OralAudioPayload.from(audio));
    }
    /** Không lưu audio preview hoặc transcript vào lịch sử thi. */
    @Override public String preview(String language, MultipartFile audio) {
        access.currentUser("STUDENT"); ready();
        if (audio.getSize() > 2 * 1024 * 1024) throw new AppException(ErrorCode.ORAL_AUDIO_INVALID);
        if (!previewLimit.tryAcquire()) throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_QUOTA);
        try { return provider.transcribe(OralAudioPayload.from(audio), language); }
        finally { previewLimit.release(); }
    }
    /** Key thiếu không được âm thầm trả transcript mẫu. */ private void ready() { if (!settings.ready()) throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_NOT_READY); }
}
