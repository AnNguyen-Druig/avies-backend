package com.avies.backend.service.support;

/** Kết quả có cấu trúc; không có điểm, đáp án hoặc quyền sửa giới hạn thi. */
public record OralDecisionResult(/** true là đề xuất một follow-up. */ boolean followUp,
                                 /** Chỉ có nội dung khi followUp=true. */ String prompt) {
    /** Chuyển câu theo quyết định hợp lệ hoặc khi BE đã hết quota/ngân sách. */
    public static OralDecisionResult advance() { return new OralDecisionResult(false, null); }
}
