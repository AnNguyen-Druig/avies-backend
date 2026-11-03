package com.avies.backend.service;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.OralExamResponse;
import com.avies.backend.service.support.OralSpeechPlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;

/** Biên điều phối; controller chỉ công bố thao tác Student, các hàm expiry/claim/callback dành riêng cho worker nội bộ. */
public interface OralExamService {
    /** Dùng Start Feature 2 để chốt đề trước khi mở tiến trình hội thoại. */
    OralExamResponse open(@NotNull @Positive Long examId, @NotNull @Valid OralExamStartRequest request);
    /** Đọc trạng thái của chính Student và đồng bộ giới hạn thời gian máy chủ. */
    OralExamResponse get(@NotNull @Positive Long examId);
    /** Đọc riêng text của lượt hiện tại sau PROMPT_FINISHED, không gọi TTS hoặc thay đổi thời gian. */
    com.avies.backend.dto.response.OralQuestionTextResponse questionText(@NotNull @Positive Long examId, @NotNull UUID turnId);
    /** Chỉ tác vụ nội bộ gọi; khóa/recheck deadline để chốt EXPIRED, không yêu cầu Student còn mở tab. */
    boolean expireOverdue(Long examId);
    /** Giữ quyền riêng, không dùng hàng đợi thao tác nghiệp vụ hoặc revision của lượt đang nghe. */
    com.avies.backend.dto.response.OralLeaseResponse renewLease(@NotNull @Positive Long examId, @NotNull @Valid OralExamStartRequest request);
    /** Lệnh phiên bản/idempotency, không nhận nội dung câu trả lời. */
    OralExamResponse execute(@NotNull @Positive Long examId, @NotNull @Valid OralExamCommandRequest request);
    /** Lấy prompt nội bộ trong transaction ngắn, chỉ cho tab hiện hành chuẩn bị/nghe đúng lượt. */
    OralSpeechPlan speechPlan(Long examId, OralSpeechRequest request);
    /** Xác thực stream đang ANSWERING; ngôn ngữ lấy từ turn, không nhận từ client. */
    String streamingLanguage(Long examId, OralSpeechRequest request);
    /** Chỉ transport nội bộ được đưa final Azure vào đây; không có HTTP nhận transcript từ Student. */
    OralExamResponse completeStreamedAudio(Long examId, UUID job, OralAudioRequest request,
            com.avies.backend.service.support.OralAudioPayload audio, String transcript);
    /** Sau khi TTS xong phải kiểm tra lại quyền, turn và deadline trước khi đánh dấu sẵn sàng. */
    void confirmSpeech(Long examId, OralSpeechRequest request, String language);
    /** Gắn file đã kiểm tra vào submission, không gọi STT trong transaction. */
    OralExamResponse attachAudio(Long examId, UUID submissionId, OralAudioRequest request, com.avies.backend.service.support.OralAudioPayload audio);
    /** Worker tin cậy giành claim trước khi gọi Groq; không có HTTP public cho hàm này. */
    com.avies.backend.service.support.OralTranscriptionWork claimTranscription(Long examId, UUID submissionId);
    /** Chốt STT bằng nonce, kiểm tra lại thời hạn/turn để bỏ kết quả về muộn. */
    void completeTranscription(com.avies.backend.service.support.OralTranscriptionWork work, String transcript, Integer errorCode);
    /** Giành claim và lấy context snapshot đã có transcript, chỉ worker nội bộ được gọi. */
    com.avies.backend.service.support.OralDecisionWork claimDecision(Long examId, UUID submissionId);
    /** Chốt quyết định theo nonce/turn/deadline; lỗi kỹ thuật giữ job để RETRY. */
    void completeDecision(com.avies.backend.service.support.OralDecisionWork work, com.avies.backend.service.support.OralDecisionResult result, Integer errorCode);
    /** Callback worker TTS tin cậy; không có endpoint public trong giai đoạn nền móng. */
    OralExamResponse promptReady(Long examId, UUID turnId, String language);
    /** Callback STT tin cậy; nội dung không được lấy trực tiếp từ HTTP Student. */
    OralExamResponse transcriptReady(Long examId, UUID submissionId, String transcript);
    /** Callback AI tin cậy; BE vẫn áp giới hạn follow-up toàn bài. */
    OralExamResponse decide(Long examId, UUID submissionId, boolean followUp, String followUpPrompt);
    /** Ghi lỗi kỹ thuật cho đúng công việc, giữ nguyên bằng chứng để retry. */
    OralExamResponse technicalFailure(Long examId, UUID workId);
}
