package com.avies.backend.repository;

import com.avies.backend.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.*;

/** Claim AI độc lập STT, không dùng khóa DB trong lúc chờ nhà cung cấp. */
@Repository
public class OralDecisionRepository {
    /** Tham gia transaction hiện tại khi được service gọi. */ private final JdbcTemplate jdbc;
    /** Không mở datasource khác hoặc đọc .env. */ public OralDecisionRepository(DataSource source) { jdbc = new JdbcTemplate(source); }
    /** Code có thể triển khai trước migration mà không làm startup hỏng. */
    public boolean ready() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT count(*)=3 FROM information_schema.columns WHERE table_schema='aives' AND table_name='oral_answer_submissions' AND column_name IN ('decision_claim_id','decision_claim_until','decision_error_code')", Boolean.class));
    }
    /** Chưa nâng cấp thì báo rõ, không tạo SQL tự động trên Supabase. */ public void requireReady() { if (!ready()) throw new AppException(ErrorCode.ORAL_SCHEMA_NOT_READY); }
    /** Caller đã khóa parent/aggregate như các thao tác oral khác. */
    public boolean claim(UUID job, UUID token, OffsetDateTime now) {
        return jdbc.update("UPDATE aives.oral_answer_submissions SET decision_claim_id=?,decision_claim_until=?,decision_error_code=NULL WHERE id=? AND (decision_claim_until IS NULL OR decision_claim_until<=?)", token, now.plusSeconds(60), job, now) == 1;
    }
    /** Kết quả từ claim đã hết hạn/đổi chủ không được chốt. */
    public boolean ownsClaim(UUID job, UUID token, OffsetDateTime now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM aives.oral_answer_submissions WHERE id=? AND decision_claim_id=? AND decision_claim_until>?)", Boolean.class, job, token, now));
    }
    /** Lưu mã lỗi đã kiểm soát; không ghi response hoặc key của Google. */
    public void release(UUID job, UUID token, Integer error) {
        jdbc.update("UPDATE aives.oral_answer_submissions SET decision_claim_id=NULL,decision_claim_until=NULL,decision_error_code=? WHERE id=? AND decision_claim_id=?", error, job, token);
    }
    /** Watchdog dưới aggregate lock hủy claim cũ, giữ nguyên transcript đã chốt. */
    public void fail(UUID job, int error) { jdbc.update("UPDATE aives.oral_answer_submissions SET decision_claim_id=NULL,decision_claim_until=NULL,decision_error_code=? WHERE id=?",error,job); }
    /** Chỉ phục hồi job có transcript thật, đúng turn và còn ở bước quyết định. */
    public List<Candidate> candidates() {
        return jdbc.query("SELECT s.student_exam_id,s.id FROM aives.oral_answer_submissions s JOIN aives.oral_exam_progress p ON p.student_exam_id=s.student_exam_id WHERE p.phase='DECIDING' AND p.current_turn_id=s.turn_id AND s.status='TRANSCRIBED' AND s.transcript_text IS NOT NULL AND (s.decision_claim_until IS NULL OR s.decision_claim_until<=CURRENT_TIMESTAMP) ORDER BY s.updated_at LIMIT 8", (row, index) -> new Candidate(row.getLong(1), row.getObject(2, UUID.class)));
    }
    /** Chỉ ID để worker xin claim, không mang dữ liệu cá nhân. */ public record Candidate(long examId, UUID submissionId) { }
}
