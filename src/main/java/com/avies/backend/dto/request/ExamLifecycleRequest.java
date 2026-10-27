package com.avies.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Lệnh vòng đời không nhận dữ liệu; cho phép body rỗng hoặc đối tượng JSON rỗng. */
public class ExamLifecycleRequest {
    /** Không cho client tự gửi trạng thái, thời gian, câu trả lời hoặc điểm. */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Thao tác này không nhận dữ liệu từ người dùng.");
    }
}
