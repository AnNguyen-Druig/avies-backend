package com.avies.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Tạo/tiếp tục tiến trình với danh tính tab; không nhận cấu hình bài thi từ Student. */
public record OralExamStartRequest(
        /** UUID tạo một lần ở tab và giữ khi refresh. */ @NotNull UUID clientSessionId) {
    /** Chặn trường ngoài hợp đồng như thời gian, câu hỏi, transcript hoặc điểm. */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Dữ liệu yêu cầu không hợp lệ."); }
}
