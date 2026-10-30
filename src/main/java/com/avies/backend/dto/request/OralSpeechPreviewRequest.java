package com.avies.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

/** Chỉ đọc mẫu cố định, không biến endpoint thành dịch vụ đọc văn bản tùy ý. */
public record OralSpeechPreviewRequest(/** Ngôn ngữ mẫu Việt hoặc Anh. */ @NotNull @Pattern(regexp = "vi|en") String language) {
    /** Không đọc văn bản tùy ý do client đưa vào. */
    @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Dữ liệu yêu cầu không hợp lệ."); }
}
