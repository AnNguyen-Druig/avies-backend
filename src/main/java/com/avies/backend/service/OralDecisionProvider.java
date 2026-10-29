package com.avies.backend.service;

import com.avies.backend.service.support.OralDecisionResult;
import com.avies.backend.service.support.OralDecisionWork;

/** Chỉ đề xuất hội thoại; BE luôn giữ quyền quyết định lịch, quota và trạng thái thi. */
public interface OralDecisionProvider {
    /** Gọi bên ngoài transaction, không trả fallback giả khi lỗi AI. */
    OralDecisionResult decide(OralDecisionWork work);
}
