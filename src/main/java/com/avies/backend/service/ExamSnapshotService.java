package com.avies.backend.service;

import com.avies.backend.dto.snapshot.ExamGradingContext;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** SPI nội bộ cho Feature 3/4; caller phải xác thực và kiểm tra quyền trước khi dùng, không phải REST API. */
public interface ExamSnapshotService {
    /** Chỉ trả bằng chứng đã chốt; không đọc đáp án hoặc rubric từ ngân hàng hiện tại. */
    ExamGradingContext loadForGrading(@NotNull @Positive Long studentExamId);
}
