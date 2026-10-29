package com.avies.backend.service;

import com.avies.backend.dto.request.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** Audio chỉ dành cho Student đúng chủ lượt và đúng turn đang mở. */
public interface OralSpeechService {
    /** Chuẩn bị cache nội bộ; không cấp quyền phát hoặc mở ngân sách trả lời. */
    void prefetch(com.avies.backend.service.support.OralSpeechPlan plan);
    /** Phát câu hỏi snapshot hiện tại, nghe lại không tạo turn hoặc tăng quota. */
    byte[] current(@NotNull @Positive Long examId, @NotNull @Valid OralSpeechRequest request);
    /** Nghe thử câu cố định mà không bắt đầu hoặc sửa bất kỳ bài thi nào. */
    byte[] preview(@NotNull @Valid OralSpeechPreviewRequest request);
}
