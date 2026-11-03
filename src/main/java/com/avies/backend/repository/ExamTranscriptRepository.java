package com.avies.backend.repository;

import com.avies.backend.dto.projection.ExamAnswerEvidenceRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Hợp đồng đọc bằng chứng thi; implementation JDBC tham gia transaction do service quản lý. */
public interface ExamTranscriptRepository {
    /** Caller đã khóa phiên, lượt thi và bộ câu; khóa bằng chứng trong lúc Finish. */
    List<ExamAnswerEvidenceRow> lockSavedAnswers(Long examId);

    /** Kiểm tra có bằng chứng trả lời, không tạo dữ liệu thay thế. */
    boolean hasStudentAnswer(Long examId);

    /** Kiểm tra tiến trình oral đã tồn tại để giữ kín đề của lượt thi. */
    boolean hasOralProgress(Long examId);

    /** Empty khi chưa có tiến trình mới; kết quả dựa trên bằng chứng đã lưu đến timestamp. */
    Optional<Boolean> oralCompletionState(Long examId, OffsetDateTime timestamp);
}
