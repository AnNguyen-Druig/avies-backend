package com.avies.backend.repository;

import com.avies.backend.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.*;

/** Metadata audio/claim không trộn vào snapshot hoặc question bank; caller giữ khóa parent như oral. */
@Repository
public class OralAudioRepository {
    /** Tham gia transaction hiện tại. */ private final JdbcTemplate jdbc;
    /** Datasource do Spring quản lý. */ public OralAudioRepository(DataSource source) { jdbc = new JdbcTemplate(source); }
    /** Thiếu migration mới không làm các API cũ hoặc startup BE bị hỏng. */
    public boolean ready() {
        return jdbc.queryForObject("SELECT count(*)=8 FROM information_schema.columns WHERE table_schema='aives' AND table_name='oral_answer_submissions' AND column_name IN ('audio_sha256','audio_media_type','audio_bytes','audio_received_at','audio_storage_id','stt_claim_id','stt_claim_until','stt_error_code')", Boolean.class);
    }
    /** Chặn rõ khi chưa áp dụng migration STT. */ public void requireReady() { if (!ready()) throw new AppException(ErrorCode.ORAL_SCHEMA_NOT_READY); }
    /** Ghi một lần, chỉ sau khi file đã publish thành công. */
    public void attach(UUID job, String hash, String media, int bytes, OffsetDateTime now, String storageId) {
        jdbc.update("UPDATE aives.oral_answer_submissions SET audio_sha256=?,audio_media_type=?,audio_bytes=?,audio_received_at=?,audio_storage_id=? WHERE id=?", hash, media, bytes, now, storageId, job);
    }
    /** Claim được ghi dưới cùng parent lock, worker khác không thể cùng lấy job còn lease. */
    public boolean claim(UUID job, UUID token, OffsetDateTime now) {
        return jdbc.update("UPDATE aives.oral_answer_submissions SET stt_claim_id=?,stt_claim_until=?,stt_error_code=NULL WHERE id=? AND (stt_claim_until IS NULL OR stt_claim_until<=?)", token, now.plusSeconds(60), job, now) == 1;
    }
    /** Chỉ đúng nonce còn hiệu lực mới được ghi kết quả sau gọi mạng. */
    public boolean ownsClaim(UUID job, UUID token, OffsetDateTime now) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM aives.oral_answer_submissions WHERE id=? AND stt_claim_id=? AND stt_claim_until>?)", Boolean.class, job, token, now);
    }
    /** Giải phóng claim với mã lỗi kiểm soát, không lưu lỗi provider thô. */
    public void release(UUID job, UUID token, Integer code) {
        jdbc.update("UPDATE aives.oral_answer_submissions SET stt_claim_id=NULL,stt_claim_until=NULL,stt_error_code=? WHERE id=? AND stt_claim_id=?", code, job, token);
    }
    /** Watchdog đã giữ aggregate lock; hủy nonce cũ để callback về muộn không thắng RETRY. */
    public void fail(UUID job, int error) { jdbc.update("UPDATE aives.oral_answer_submissions SET stt_claim_id=NULL,stt_claim_until=NULL,stt_error_code=? WHERE id=?",error,job); }
    /** Đọc batch nhỏ để phục hồi sau restart; claim mới thực sự quyết định quyền xử lý. */
    public List<Candidate> candidates(String storageId) {
        return jdbc.query("SELECT s.student_exam_id,s.id FROM aives.oral_answer_submissions s JOIN aives.oral_exam_progress p ON p.student_exam_id=s.student_exam_id WHERE p.phase='TRANSCRIBING' AND p.current_turn_id=s.turn_id AND s.status='PROCESSING' AND s.audio_storage_id=? AND s.audio_sha256 IS NOT NULL AND (s.stt_claim_until IS NULL OR s.stt_claim_until<=CURRENT_TIMESTAMP) ORDER BY s.updated_at LIMIT 8",
                (row, index) -> new Candidate(row.getLong(1), row.getObject(2, UUID.class)), storageId);
    }
    /** ID không chứa dữ liệu cá nhân hoặc nội dung trả lời. */ public record Candidate(long examId, UUID submissionId) { }
}
