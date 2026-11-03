package com.avies.backend.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Hợp đồng lưu claim AI độc lập STT; caller quản lý transaction và khóa aggregate. */
public interface OralDecisionRepository {
    /** Kiểm tra các cột migration AI, không tự thay đổi schema. */
    boolean ready();

    /** Báo lỗi cấu hình schema khi chưa áp dụng migration AI. */
    void requireReady();

    /** Giành claim AI dưới parent lock; claim còn hiệu lực không bị chiếm. */
    boolean claim(UUID job, UUID token, OffsetDateTime now);

    /** Kết quả từ claim hết hạn hoặc đổi chủ không được chốt. */
    boolean ownsClaim(UUID job, UUID token, OffsetDateTime now);

    /** Giải phóng đúng claim và lưu mã lỗi đã kiểm soát. */
    void release(UUID job, UUID token, Integer error);

    /** Watchdog đã giữ aggregate lock; hủy claim, giữ nguyên transcript. */
    void fail(UUID job, int error);

    /** Chỉ tìm job đã có transcript, đúng turn và đang chờ quyết định. */
    List<Candidate> candidates();

    /** Chỉ ID để worker xin claim, không mang dữ liệu cá nhân. */
    record Candidate(long examId, UUID submissionId) { }
}
