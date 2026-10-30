package com.avies.backend.service.impl;

import com.avies.backend.dto.response.OralExamRuntimeResponse;
import com.avies.backend.service.OralExamRuntimeService;
import com.avies.backend.service.support.ExamAccessPolicy;
import com.avies.backend.config.OralSpeechProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mặc định đóng cổng bắt đầu trên FE khi chưa nối STT/TTS/AI, không giả lập trên database thật. */
@Service
@RequiredArgsConstructor
public class OralExamRuntimeServiceImpl implements OralExamRuntimeService {
    /** Kiểm tra role trong DB thay vì chỉ tin authority JWT. */ private final ExamAccessPolicy access;
    /** TTS sẵn sàng riêng, không tuyên bố STT/AI đã hoạt động. */ private final OralSpeechProperties speech;
    /** STT kiểm tra mic độc lập, không khẳng định AI follow-up đã nối. */ private final com.avies.backend.config.OralTranscriptionProperties transcription;
    /** Chỉ kiểm tra schema, không áp migration khi startup. */ private final com.avies.backend.repository.OralAudioRepository audioRepository;
    /** Flag/key/model quyết định hội thoại riêng. */ private final com.avies.backend.config.OralDecisionProperties decision;
    /** Cần claim bền để không hỏi/chuyển câu trùng sau restart. */ private final com.avies.backend.repository.OralDecisionRepository decisionRepository;

    /** Cấu hình đầy đủ mới bật LIVE; key/quota vẫn cần xác nhận bằng việc sử dụng dịch vụ thật. */
    @Override
    @Transactional(readOnly = true)
    public OralExamRuntimeResponse get() {
        access.currentUser("STUDENT");
        boolean audioReady=transcription.ready() && audioRepository.ready();
        boolean ready=speech.ready() && audioReady && decision.ready() && decisionRepository.ready();
        return new OralExamRuntimeResponse(ready ? "LIVE" : "FOUNDATION", ready,
                ready ? "Đã cấu hình giọng đọc, nhận dạng và AI hội thoại. Bắt đầu theo lịch máy chủ; hạn mức dịch vụ vẫn được kiểm tra khi xử lý."
                        : "Chưa đủ cấu hình TTS, STT, AI hội thoại hoặc migration. Có thể thử thiết bị đã cấu hình trước khi bắt đầu.", speech.ready(), transcription.ready(), audioReady);
    }
}
