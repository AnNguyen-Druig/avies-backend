package com.avies.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Chặn trường ngoài hợp đồng, đặc biệt các trường quyền hạn do máy chủ quản lý.
 */
public abstract class QuestionWriteRequest {
    /**
     * Từ chối trường lạ thay vì âm thầm bỏ qua yêu cầu thay đổi trạng thái hoặc chủ sở hữu.
     */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Trường không được hỗ trợ trong yêu cầu câu hỏi.");
    }
}
