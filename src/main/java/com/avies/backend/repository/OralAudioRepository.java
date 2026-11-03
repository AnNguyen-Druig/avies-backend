package com.avies.backend.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Hợp đồng lưu metadata audio và claim STT; caller quản lý transaction và khóa aggregate. */
public interface OralAudioRepository {
    /** Kiểm tra các cột migration STT, không tự thay đổi schema. */
    boolean ready();

    /** Báo lỗi cấu hình schema khi chưa áp dụng migration STT. */
    void requireReady();

    /** Gắn metadata một lần sau khi file đã publish thành công. */
    void attach(UUID job, String hash, String media, int bytes, OffsetDateTime now, String storageId);

    /** Giành claim STT dưới parent lock; claim còn hiệu lực không bị chiếm. */
    boolean claim(UUID job, UUID token, OffsetDateTime now);

    /** Chỉ nonce còn hiệu lực mới được chốt kết quả. */
    boolean ownsClaim(UUID job, UUID token, OffsetDateTime now);

    /** Giải phóng đúng claim và lưu mã lỗi đã kiểm soát. */
    void release(UUID job, UUID token, Integer code);

    /** Watchdog đã giữ aggregate lock; hủy nonce cũ trước khi retry. */
    void fail(UUID job, int error);

    /** Đọc batch phục hồi của đúng audio storage; service vẫn phải giành claim. */
    List<Candidate> candidates(String storageId);

    /** ID không chứa dữ liệu cá nhân hoặc nội dung trả lời. */
    record Candidate(long examId, UUID submissionId) { }
}
