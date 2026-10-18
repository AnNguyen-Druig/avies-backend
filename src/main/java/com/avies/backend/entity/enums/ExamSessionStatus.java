package com.avies.backend.entity.enums;

/** Trạng thái phiên thi; phải đồng bộ với CHECK hiện có trên exam_sessions. */
public enum ExamSessionStatus {
    /** Phiên thi sắp diễn ra. */
    UPCOMING,
    /** Phiên thi đang diễn ra. */
    ONGOING,
    /** Phiên thi đã hoàn tất. */
    COMPLETED
}
