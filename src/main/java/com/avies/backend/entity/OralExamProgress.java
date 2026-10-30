package com.avies.backend.entity;

import com.avies.backend.entity.enums.*;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Aggregate lưu bằng JDBC trong transaction JPA hiện có; không tạo schema bằng Hibernate. */
@Data
public class OralExamProgress {
    /** Lượt thi gốc, cũng là khóa chính tiến trình. */ private Long studentExamId;
    /** Bước hội thoại hiện tại. */ private OralExamPhase phase;
    /** Bước được giữ để tiếp tục sau tạm dừng/lỗi. */ private OralExamPhase recoveryPhase;
    /** Câu chính hiện tại theo thứ tự snapshot. */ private Integer currentQuestionOrder;
    /** Lượt hỏi ổn định, nghe lại không sinh ID mới. */ private UUID currentTurnId;
    /** Tổng follow-up đã phát sinh trên toàn bài. */ private Integer followUpsUsed;
    /** Giới hạn được chốt từ phiên thi khi mở hội thoại. */ private Integer maxFollowUps;
    /** Số câu chính đã chốt từ snapshot. */ private Integer mainCount;
    /** Mốc bắt đầu tính ngân sách lượt trả lời đang mở. */ private OffsetDateTime answerOpenedAt;
    /** Tab giữ quyền tương tác, không thay thế danh tính JWT. */ private UUID clientSessionId;
    /** Lease ngắn để hai tab không cùng điều khiển lượt thi. */ private OffsetDateTime leaseUntil;
    /** Phiên bản chống lệnh từ giao diện đã cũ; heartbeat không tăng phiên bản. */ private Long revision;
    /** Mốc thay đổi theo đồng hồ máy chủ. */ private OffsetDateTime updatedAt;
    /** Ngân sách và kết quả riêng của từng câu chính. */ private List<Question> questions = new ArrayList<>();
    /** Lịch sử lượt hỏi và câu trả lời, chỉ nội bộ mới có prompt. */ private List<Turn> turns = new ArrayList<>();
    /** Lượt xử lý STT/AI, không chứa audio hoặc key dịch vụ. */ private List<Submission> submissions = new ArrayList<>();

    /** Dữ liệu tiến trình của một liên kết câu đã snapshot. */
    @Data
    public static class Question {
        /** ID liên kết lịch sử, không phải ID question bank. */ private Long studentExamQuestionId;
        /** Lượt thi chứa câu. */ private Long studentExamId;
        /** Thứ tự chính thức trong đề. */ private Integer questionOrder;
        /** Trạng thái thực hiện. */ private OralQuestionStatus status;
        /** Null là không có giới hạn riêng; vẫn bị chặn bởi hạn slot. */ private Long answerBudgetMs;
        /** Tổng thời gian trả lời chính và follow-up đã dùng. */ private Long answerUsedMs;
        /** Nguyên nhân chưa trả lời/lỗi kỹ thuật, không phải điểm. */ private String outcomeReason;
    }

    /** Lượt hỏi được tạo đúng một lần, không sửa nội dung đã phát. */
    @Data
    public static class Turn {
        /** Mã ổn định dùng cho replay và callback. */ private UUID id;
        /** Lượt thi sở hữu. */ private Long studentExamId;
        /** Câu chính chứa lượt hỏi. */ private Long studentExamQuestionId;
        /** 0 là câu chính, số dương là follow-up của câu đó. */ private Integer followUpIndex;
        /** Nội dung nội bộ, không trả về DTO của Student. */ private String promptText;
        /** vi/en do worker xác định; null khi chưa chuẩn bị. */ private String language;
        /** Chỉ worker tin cậy xác nhận giọng đọc sẵn sàng. */ private Boolean promptReady;
        /** PENDING/PROCESSING/ANSWERED/NO_ANSWER/TECHNICAL_ERROR. */ private String answerStatus;
        /** Transcript thật, null nếu chưa có; không tạo câu trả lời thay thế. */ private String answerText;
        /** Thời điểm tạo lượt hỏi. */ private OffsetDateTime createdAt;
        /** Thời điểm máy chủ nhận kết quả STT hợp lệ. */ private OffsetDateTime answeredAt;
    }

    /** Công việc xử lý một lượt trả lời, retry không tạo bản ghi trùng. */
    @Data
    public static class Submission {
        /** Hash nội bộ của audio đã chốt, không trả về HTTP. */ private String audioSha256;
        /** Vùng lưu đã nhận file, tránh worker của máy khác đọc nhầm audio. */ private String audioStorageId;
        /** Mã lỗi STT kiểm soát, không chứa response nhà cung cấp. */ private Integer sttErrorCode;
        /** Mã lỗi AI kiểm soát, không chứa prompt hoặc response nhà cung cấp. */ private Integer decisionErrorCode;
        /** Worker chỉ được hoàn tất đúng mã đã được máy chủ cấp. */ private UUID id;
        /** Lượt thi sở hữu. */ private Long studentExamId;
        /** Lượt hỏi được xử lý. */ private UUID turnId;
        /** PROCESSING/TRANSCRIBED/SUCCEEDED/EMPTY/FAILED/CANCELLED. */ private String status;
        /** Transcript STT được kiểm tra trước khi lưu. */ private String transcriptText;
        /** Quyết định đã chốt để callback lặp không đổi kết quả. */ private String decisionKind;
        /** Nội dung follow-up đã chốt, chỉ dùng nội bộ. */ private String decisionText;
        /** Mốc thay đổi theo máy chủ. */ private OffsetDateTime updatedAt;
    }
}
