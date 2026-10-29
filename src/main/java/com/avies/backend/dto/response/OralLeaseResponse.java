package com.avies.backend.dto.response;

import java.time.OffsetDateTime;

/** Receipt giữ quyền nhỏ, không khóa/ghi lại cả hội thoại hoặc thay đổi ngân sách thi. */
public record OralLeaseResponse(/** Mốc máy chủ để FE nội suy. */ OffsetDateTime serverTime,
                                /** Hạn giữ quyền; null khi lượt thi đã kết thúc. */ OffsetDateTime leaseUntil,
                                /** Yêu cầu FE đọc kết quả cuối thay vì báo mất quyền. */ boolean terminal) {
    /** Receipt cho lượt thi vẫn hoạt động. */
    public OralLeaseResponse(OffsetDateTime serverTime, OffsetDateTime leaseUntil) { this(serverTime, leaseUntil, false); }
}
