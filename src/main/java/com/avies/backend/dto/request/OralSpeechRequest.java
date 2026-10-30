package com.avies.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.UUID;

/** Chỉ định đúng lượt cần nghe, không nhận nội dung câu hỏi hoặc ngôn ngữ tự khai. */
public record OralSpeechRequest(
        /** Tab đang giữ lease. */ @NotNull UUID clientSessionId,
        /** Lượt hỏi do backend đã cấp. */ @NotNull UUID turnId,
        /** Phiên bản giao diện trước khi yêu cầu audio. */ @NotNull @PositiveOrZero Long expectedRevision) {
    /** Không nhận nội dung đề/ngôn ngữ hoặc token tự khai ngoài hợp đồng. */
    @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Dữ liệu yêu cầu không hợp lệ."); }
}
