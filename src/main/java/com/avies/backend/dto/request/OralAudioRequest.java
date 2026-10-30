package com.avies.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Multipart metadata chỉ xác định tab và turn; không nhận transcript tự khai. */
public record OralAudioRequest(/** Tab đang giữ quyền. */ @NotNull UUID clientSessionId,
        /** Lượt đã dành submission bằng SUBMIT. */ @NotNull UUID turnId) {
    /** Không chấp nhận đường dẫn, ngôn ngữ hoặc nội dung trả lời ngoài hợp đồng. */
    @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Dữ liệu yêu cầu không hợp lệ."); }
}
