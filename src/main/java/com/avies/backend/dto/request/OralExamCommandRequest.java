package com.avies.backend.dto.request;

import com.avies.backend.entity.enums.OralExamAction;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.UUID;

/** Lệnh có kiểm soát phiên bản và idempotency, không nhận câu trả lời tự khai từ trình duyệt. */
public record OralExamCommandRequest(
        /** Mã mới cho mỗi thao tác, dùng lại nguyên payload khi retry. */ @NotNull UUID requestId,
        /** Tab đang giữ lease. */ @NotNull UUID clientSessionId,
        /** Phiên bản từ lần đọc tiến trình gần nhất. */ @NotNull @PositiveOrZero Long expectedRevision,
        /** Mã lượt hiện tại; null chỉ khi bước hiện tại không có lượt hỏi. */ UUID turnId,
        /** Loại thao tác được máy chủ cho phép. */ @NotNull OralExamAction action,
        /** Chỉ có ở REVISIT; không cho Student chọn câu ngoài hàng đợi. */ @Positive Integer targetQuestionOrder) {
    /** Không âm thầm bỏ qua transcript, thời gian hoặc trường không được công bố. */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Dữ liệu yêu cầu không hợp lệ."); }
}
