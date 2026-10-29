package com.avies.backend.service;

import com.avies.backend.dto.response.OralExamRuntimeResponse;

/** Biên khai báo khả năng pipeline; phần mô phỏng chỉ được cung cấp từ chương trình test riêng. */
public interface OralExamRuntimeService {
    /** Đọc khả năng hiện tại, không tạo hoặc bắt đầu bài thi. */
    OralExamRuntimeResponse get();
}
