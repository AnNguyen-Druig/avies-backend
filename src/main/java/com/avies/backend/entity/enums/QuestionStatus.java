package com.avies.backend.entity.enums;

/** Trạng thái xét duyệt; lưu trữ được quản lý riêng bằng thời điểm lưu trữ. */
public enum QuestionStatus {
    /** Bản nháp được phép chỉnh sửa. */
    DRAFT,
    /** Câu hỏi đang chờ giảng viên xét duyệt. */
    PENDING_REVIEW,
    /** Câu hỏi đã được duyệt vào ngân hàng chính thức. */
    APPROVED,
    /** Câu hỏi bị từ chối và cần chỉnh sửa trước khi gửi lại. */
    REJECTED
}
