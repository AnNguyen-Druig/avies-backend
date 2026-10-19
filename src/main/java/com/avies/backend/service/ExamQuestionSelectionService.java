package com.avies.backend.service;

import com.avies.backend.dto.response.SelectedMainQuestion;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/** Hợp đồng nội bộ, không phải API công khai hoặc thay thế xác thực của Start Exam. */
public interface ExamQuestionSelectionService {
    /**
     * Cần transaction ghi đã mở bởi caller tin cậy; tự khóa parent/child theo giao thức chung.
     * Caller phải kiểm tra JWT, role, ownership, phân công và thời gian được phép bắt đầu.
     * Bộ đầy đủ đã lưu luôn được trả lại; bộ thiếu/sai thứ tự bị chặn, không sửa hoặc bốc lại.
     * Caller phải để lỗi làm rollback toàn workflow; selection không đổi status hoặc startedAt.
     */
    List<SelectedMainQuestion> selectForStart(@NotNull @Positive Long studentExamId);
}
