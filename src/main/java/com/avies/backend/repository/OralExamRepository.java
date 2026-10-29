package com.avies.backend.repository;

import com.avies.backend.entity.OralExamProgress;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Lưu aggregate hội thoại bằng JDBC, tham gia cùng transaction/khóa parent với Feature 2. */
@Repository
public class OralExamRepository {
    /** Không đọc .env hoặc mở datasource ngoài Spring. */ private final JdbcTemplate jdbc;

    /** Dùng datasource ứng dụng, hoặc datasource cô lập do context test cung cấp. */
    public OralExamRepository(DataSource source) { jdbc = new JdbcTemplate(source); }

    /** Caller phải khóa phiên và lượt thi trước; NOWAIT trả xung đột thay vì chờ vô hạn. */
    public Optional<OralExamProgress> findLocked(long examId) {
        return find(examId,true);
    }

    /** Caller dùng REPEATABLE_READ để cả aggregate thuộc cùng snapshot, không khóa hàng khi polling. */
    public Optional<OralExamProgress> findSnapshot(long examId) { return find(examId,false); }

    /** Chỉ đường ghi mới dùng NOWAIT; poll không chặn STT/AI callback hoặc heartbeat. */
    private Optional<OralExamProgress> find(long examId, boolean lock) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT to_regclass('aives.oral_exam_progress') IS NOT NULL
                   AND to_regclass('aives.oral_question_progress') IS NOT NULL
                   AND to_regclass('aives.oral_exam_turns') IS NOT NULL
                   AND to_regclass('aives.oral_answer_submissions') IS NOT NULL
                   AND to_regclass('aives.oral_exam_commands') IS NOT NULL
                """, Boolean.class))) throw new AppException(ErrorCode.ORAL_SCHEMA_NOT_READY);
        String suffix=lock ? " FOR UPDATE NOWAIT" : "";
        var rows = jdbc.query("SELECT * FROM aives.oral_exam_progress WHERE student_exam_id=?"+suffix,
                BeanPropertyRowMapper.newInstance(OralExamProgress.class), examId);
        if (rows.isEmpty()) return Optional.empty();
        var progress = rows.getFirst();
        progress.setQuestions(jdbc.query("SELECT * FROM aives.oral_question_progress WHERE student_exam_id=? ORDER BY question_order"+suffix,
                BeanPropertyRowMapper.newInstance(OralExamProgress.Question.class), examId));
        progress.setTurns(jdbc.query("SELECT * FROM aives.oral_exam_turns WHERE student_exam_id=? ORDER BY created_at,id"+suffix,
                BeanPropertyRowMapper.newInstance(OralExamProgress.Turn.class), examId));
        progress.setSubmissions(jdbc.query("SELECT * FROM aives.oral_answer_submissions WHERE student_exam_id=? ORDER BY updated_at,id"+suffix,
                BeanPropertyRowMapper.newInstance(OralExamProgress.Submission.class), examId));
        return Optional.of(progress);
    }

    /** Một UPDATE đúng client; không phụ thuộc revision/turn vốn thay đổi khi worker chuyển bước. */
    public boolean renewLease(long examId, UUID client, OffsetDateTime now, OffsetDateTime until) {
        return jdbc.update("UPDATE aives.oral_exam_progress p SET lease_until=? FROM aives.student_exams e,aives.exam_sessions s WHERE p.student_exam_id=? AND p.client_session_id=? AND p.phase NOT IN ('COMPLETED','EXPIRED') AND e.id=p.student_exam_id AND e.session_id=s.id AND e.status='IN_PROGRESS' AND s.status='ONGOING' AND e.scheduled_start_at<=? AND e.scheduled_end_at>?",until,examId,client,now,now)==1;
    }

    /** Đọc trạng thái cuối sau UPDATE lease thất bại, không khóa hoặc tải cả hội thoại. */
    public boolean leaseEnded(long examId, OffsetDateTime now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM aives.student_exams e
                  LEFT JOIN aives.oral_exam_progress p ON p.student_exam_id=e.id
                  WHERE e.id=? AND (e.status IN ('COMPLETED','EXPIRED')
                    OR p.phase IN ('COMPLETED','EXPIRED') OR e.scheduled_end_at<=?))
                """, Boolean.class, examId, now));
    }

    /** Tạo parent trước khi tạo các dòng câu con; currentTurnId chưa có FK vòng. */
    public void insert(OralExamProgress p) {
        jdbc.update("""
                INSERT INTO aives.oral_exam_progress(student_exam_id,phase,recovery_phase,current_question_order,current_turn_id,
                    follow_ups_used,max_follow_ups,main_count,answer_opened_at,client_session_id,lease_until,revision,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, p.getStudentExamId(), p.getPhase().name(), name(p.getRecoveryPhase()), p.getCurrentQuestionOrder(), p.getCurrentTurnId(),
                p.getFollowUpsUsed(), p.getMaxFollowUps(), p.getMainCount(), p.getAnswerOpenedAt(), p.getClientSessionId(), p.getLeaseUntil(), p.getRevision(), p.getUpdatedAt());
        for (var q : p.getQuestions()) jdbc.update("""
                INSERT INTO aives.oral_question_progress(student_exam_question_id,student_exam_id,question_order,status,answer_budget_ms,answer_used_ms,outcome_reason)
                VALUES (?,?,?,?,?,?,?)
                """, q.getStudentExamQuestionId(), p.getStudentExamId(), q.getQuestionOrder(), q.getStatus().name(), q.getAnswerBudgetMs(), q.getAnswerUsedMs(), q.getOutcomeReason());
    }

    /** Ghi phần tiến trình dưới parent lock; không sửa snapshot hoặc ngân sách gốc. */
    public void save(OralExamProgress p) {
        jdbc.update("""
                UPDATE aives.oral_exam_progress SET phase=?,recovery_phase=?,current_question_order=?,current_turn_id=?,
                    follow_ups_used=?,answer_opened_at=?,client_session_id=?,lease_until=?,revision=?,updated_at=? WHERE student_exam_id=?
                """, p.getPhase().name(), name(p.getRecoveryPhase()), p.getCurrentQuestionOrder(), p.getCurrentTurnId(),
                p.getFollowUpsUsed(), p.getAnswerOpenedAt(), p.getClientSessionId(), p.getLeaseUntil(), p.getRevision(), p.getUpdatedAt(), p.getStudentExamId());
        for (var q : p.getQuestions()) jdbc.update("""
                UPDATE aives.oral_question_progress SET status=?,answer_used_ms=?,outcome_reason=?
                WHERE student_exam_question_id=? AND student_exam_id=?
                """, q.getStatus().name(), q.getAnswerUsedMs(), q.getOutcomeReason(), q.getStudentExamQuestionId(), p.getStudentExamId());
        for (var t : p.getTurns()) jdbc.update("""
                UPDATE aives.oral_exam_turns SET language=?,prompt_ready=?,answer_status=?,answer_text=?,answered_at=? WHERE id=? AND student_exam_id=?
                """, t.getLanguage(), t.getPromptReady(), t.getAnswerStatus(), t.getAnswerText(), t.getAnsweredAt(), t.getId(), p.getStudentExamId());
        for (var s : p.getSubmissions()) jdbc.update("""
                UPDATE aives.oral_answer_submissions SET status=?,transcript_text=?,decision_kind=?,decision_text=?,updated_at=?
                WHERE id=? AND student_exam_id=?
                """, s.getStatus(), s.getTranscriptText(), s.getDecisionKind(), s.getDecisionText(), s.getUpdatedAt(), s.getId(), p.getStudentExamId());
    }

    /** Tạo lượt hỏi một lần và lưu lời AI vào transcripts với thứ tự do BE cấp. */
    public void insertTurn(OralExamProgress.Turn t) {
        jdbc.update("""
                INSERT INTO aives.oral_exam_turns(id,student_exam_id,student_exam_question_id,follow_up_index,prompt_text,language,prompt_ready,answer_status,created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, t.getId(), t.getStudentExamId(), t.getStudentExamQuestionId(), t.getFollowUpIndex(), t.getPromptText(), t.getLanguage(), t.getPromptReady(), t.getAnswerStatus(), t.getCreatedAt());
        jdbc.update("""
                INSERT INTO aives.transcripts(student_exam_question_id,turn_no,turn_type,content,created_at,oral_turn_id)
                VALUES (?,?,?,?,?,?)
                """, t.getStudentExamQuestionId(), t.getFollowUpIndex() * 2 + 1,
                t.getFollowUpIndex() == 0 ? "AI_MAIN_Q" : "AI_FOLLOW_UP", t.getPromptText(), t.getCreatedAt(), t.getId());
    }

    /** Dành mã xử lý riêng; không nhận nội dung câu trả lời từ request Student. */
    public void insertSubmission(OralExamProgress.Submission s) {
        jdbc.update("INSERT INTO aives.oral_answer_submissions(id,student_exam_id,turn_id,status,updated_at) VALUES (?,?,?,?,?)",
                s.getId(), s.getStudentExamId(), s.getTurnId(), s.getStatus(), s.getUpdatedAt());
    }

    /** Chỉ callback STT nội bộ ghi bằng chứng có nội dung, đúng một lần cho mỗi lượt hỏi. */
    public void insertAnswer(OralExamProgress.Turn turn, UUID submissionId) {
        jdbc.update("""
                INSERT INTO aives.transcripts(student_exam_question_id,turn_no,turn_type,content,created_at,oral_turn_id,oral_submission_id)
                VALUES (?,?,'STUDENT_ANS',?,?,?,?)
                """, turn.getStudentExamQuestionId(), turn.getFollowUpIndex() * 2 + 2, turn.getAnswerText(), turn.getAnsweredAt(), turn.getId(), submissionId);
    }

    /** Không tự nhận dữ liệu legacy đã có hội thoại là dữ liệu của bộ điều phối mới. */
    public boolean hasLegacyConversation(long examId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM aives.transcripts t JOIN aives.student_exam_questions q ON q.id=t.student_exam_question_id WHERE q.student_exam_id=?)
                """, Boolean.class, examId));
    }

    /** Cùng requestId chỉ hợp lệ với nguyên payload ban đầu. */
    public Optional<String> commandHash(long examId, UUID requestId) {
        return jdbc.query("SELECT request_hash FROM aives.oral_exam_commands WHERE student_exam_id=? AND request_id=?",
                (row, index) -> row.getString(1), examId, requestId).stream().findFirst();
    }

    /** Receipt và thay đổi nghiệp vụ commit cùng transaction. */
    public void rememberCommand(long examId, UUID requestId, String hash, OffsetDateTime now) {
        jdbc.update("INSERT INTO aives.oral_exam_commands(student_exam_id,request_id,request_hash,created_at) VALUES (?,?,?,?)", examId, requestId, hash, now);
    }

    /** Enum nullable chỉ xuất tên cố định, không ghép giá trị vào SQL. */
    private String name(Enum<?> value) { return value == null ? null : value.name(); }
}
