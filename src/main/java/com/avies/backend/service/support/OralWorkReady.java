package com.avies.backend.service.support;

import java.util.UUID;

/** Tín hiệu nội bộ sau commit; scheduler vẫn phục hồi job khi restart hoặc executor đầy. */
public record OralWorkReady(long examId, UUID submissionId, Stage stage) {
    /** Hai bước dùng executor và claim DB riêng. */
    public enum Stage { STT, DECISION }
}
