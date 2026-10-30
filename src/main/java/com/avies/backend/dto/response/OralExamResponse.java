package com.avies.backend.dto.response;

import com.avies.backend.entity.enums.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** View an toàn cho Student; không có prompt, đáp án, rubric hoặc dữ liệu nhà cung cấp. */
public record OralExamResponse(
        /** Lượt thi hiện hành. */ Long studentExamId,
        /** Trạng thái Feature 2. */ StudentExamStatus status,
        /** Bước hội thoại do BE quyết định. */ OralExamPhase phase,
        /** Phiên bản phải gửi lại khi ra lệnh. */ Long revision,
        /** Thời điểm BE dựng phản hồi. */ OffsetDateTime serverTime,
        /** Hạn cuối slot, không thay đổi khi retry/refresh. */ OffsetDateTime deadline,
        /** Hạn quyền tương tác của tab hiện hành. */ OffsetDateTime leaseUntil,
        /** Số câu chính chính thức. */ Integer mainCount,
        /** Thứ tự hiện tại, null khi ở hàng đợi/tổng kết. */ Integer currentQuestionOrder,
        /** Mã lượt hỏi hiện tại. */ UUID currentTurnId,
        /** 0 là câu chính, số dương là follow-up. */ Integer followUpIndex,
        /** Ngôn ngữ giọng đọc vi/en, null khi chưa được worker chuẩn bị. */ String language,
        /** Số follow-up đã dùng trên toàn bài. */ Integer followUpsUsed,
        /** Giới hạn toàn bài từ Lecturer. */ Integer maxFollowUps,
        /** Còn lại cho câu hiện tại, null nếu không cấu hình riêng. */ Long answerRemainingMs,
        /** Mã xử lý STT/AI hiện tại, không cho client tự hoàn tất. */ UUID submissionId,
        /** Tiến độ từng câu, không phải danh sách nội dung đề. */ List<Question> questions,
        /** Chỉ transcript trả lời của chính Student. */ List<Transcript> transcripts,
        /** Server đã nhận audio bền cho submission đang xử lý. */ boolean audioReceived,
        /** Mã lỗi kỹ thuật STT đã kiểm soát nếu có. */ Integer processingErrorCode) {
    /** Tiến độ công khai của một câu chính. */
    public record Question(
            /** Thứ tự trong snapshot. */ Integer questionOrder,
            /** Kết quả thực hiện. */ OralQuestionStatus status,
            /** Ngân sách còn lại, giữ khi bỏ qua. */ Long answerRemainingMs,
            /** Phân biệt không trả lời và lỗi kỹ thuật. */ String outcomeReason) { }
    /** Transcript chỉ đọc; không bao gồm lời AI, đáp án hoặc điểm. */
    public record Transcript(
            /** Câu chính chứa câu trả lời. */ Integer questionOrder,
            /** Lượt trả lời follow-up nếu có. */ Integer followUpIndex,
            /** Nội dung đã được STT lưu. */ String content,
            /** Mốc lưu do máy chủ quyết định. */ OffsetDateTime savedAt) { }
}
