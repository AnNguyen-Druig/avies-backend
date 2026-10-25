package com.avies.backend.service;

import com.avies.backend.dto.response.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Điều phối Start/Finish/Close nguyên tử; mọi quyền và thời gian được kiểm tra ở máy chủ. */
public interface ExamLifecycleService {
    /** Bắt đầu lượt của chính sinh viên, chốt snapshot và giữ nguyên đề khi gọi lại hợp lệ. */
    StartedExamResponse start(@NotNull @Positive Long studentExamId);
    /** Chỉ hoàn thành khi Feature 3 đã lưu đủ câu trả lời hợp lệ trước hạn. */
    ExamAttemptResponse finish(@NotNull @Positive Long studentExamId);
    /** Chủ phiên đóng sau giờ kết thúc, phân biệt hoàn thành, vắng thi và hết giờ. */
    ClosedExamSessionResponse close(@NotNull @Positive Long sessionId);
}
