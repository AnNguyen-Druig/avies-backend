package com.avies.backend.repository;

import com.avies.backend.entity.OralExamProgress;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Hợp đồng persistence hội thoại; transaction và quyết định nghiệp vụ do service quản lý. */
public interface OralExamRepository {
    /** Caller khóa phiên/lượt thi trước; đọc aggregate với khóa NOWAIT. */
    Optional<OralExamProgress> findLocked(long examId);

    /** Đọc nhất quán trong transaction REPEATABLE_READ, không khóa hàng khi polling. */
    Optional<OralExamProgress> findSnapshot(long examId);

    /** Gia hạn đúng client còn quyền trong slot, không đổi revision/turn. */
    boolean renewLease(long examId, UUID client, OffsetDateTime now, OffsetDateTime until);

    /** Đọc trạng thái kết thúc sau khi không gia hạn được lease. */
    boolean leaseEnded(long examId, OffsetDateTime now);

    /** Tạo parent rồi các câu con, giữ nguyên snapshot và ngân sách ban đầu. */
    void insert(OralExamProgress progress);

    /** Lưu tiến trình dưới parent lock, không ghi đè claim/audio/snapshot. */
    void save(OralExamProgress progress);

    /** Lưu lượt hỏi cùng transcript AI trong transaction hiện tại. */
    void insertTurn(OralExamProgress.Turn turn);

    /** Tạo submission dành riêng cho lượt xử lý. */
    void insertSubmission(OralExamProgress.Submission submission);

    /** Lưu bằng chứng trả lời đã được callback nội bộ xác thực. */
    void insertAnswer(OralExamProgress.Turn turn, UUID submissionId);

    /** Kiểm tra hội thoại legacy trước khi mở tiến trình mới. */
    boolean hasLegacyConversation(long examId);

    /** Đọc hash chống dùng lại requestId với payload khác. */
    Optional<String> commandHash(long examId, UUID requestId);

    /** Lưu receipt cùng transaction thay đổi nghiệp vụ. */
    void rememberCommand(long examId, UUID requestId, String hash, OffsetDateTime now);
}
