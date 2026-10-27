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
     * Bộ đầy đủ có snapshot hợp lệ được trả lại; bộ thiếu/sai thứ tự hoặc legacy thiếu snapshot bị chặn.
     * Rubric chung được chốt lần đầu ở cấp phiên; nội dung/đáp án câu được chốt khi gán vào lượt thi.
     * Caller phải để lỗi làm rollback toàn workflow; selection không đổi status hoặc startedAt.
     */
    List<SelectedMainQuestion> selectForStart(@NotNull @Positive Long studentExamId);
}
