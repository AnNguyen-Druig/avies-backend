package com.avies.backend.repository.impl;

import com.avies.backend.repository.OralExamRepository;
import com.avies.backend.entity.OralExamProgress;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Lưu aggregate hội thoại bằng JDBC, tham gia cùng transaction/khóa parent với Feature 2. */
@Repository
public class OralExamRepositoryImpl implements OralExamRepository {
    /** Không đọc .env hoặc mở datasource ngoài Spring. */ private final JdbcTemplate jdbc;
    /** Chỉ hợp đồng nội bộ DB snake_case; không dùng mapper HTTP hoặc log JSON hội thoại. */
    private final JsonMapper json=JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    /** Chỉ cache kiểm tra schema thành công; thiếu migration vẫn được kiểm tra lại sau khi triển khai. */
    private volatile boolean schemaReady;

    /** Dùng datasource ứng dụng, hoặc datasource cô lập do context test cung cấp. */
    public OralExamRepositoryImpl(DataSource source) { jdbc = new JdbcTemplate(source); }

    /** Caller phải khóa phiên và lượt thi trước; NOWAIT trả xung đột thay vì chờ vô hạn. */
    @Override
    public Optional<OralExamProgress> findLocked(long examId) {
        return find(examId,true);
    }

    /** Caller dùng REPEATABLE_READ để cả aggregate thuộc cùng snapshot, không khóa hàng khi polling. */
    @Override
    public Optional<OralExamProgress> findSnapshot(long examId) { return find(examId,false); }

    /** Chỉ đường ghi mới dùng NOWAIT; poll không chặn STT/AI callback hoặc heartbeat. */
    private Optional<OralExamProgress> find(long examId, boolean lock) {
        if (!schemaReady) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT to_regclass('aives.oral_exam_progress') IS NOT NULL
                   AND to_regclass('aives.oral_question_progress') IS NOT NULL
                   AND to_regclass('aives.oral_exam_turns') IS NOT NULL
                   AND to_regclass('aives.oral_answer_submissions') IS NOT NULL
                   AND to_regclass('aives.oral_exam_commands') IS NOT NULL
                """, Boolean.class))) throw new AppException(ErrorCode.ORAL_SCHEMA_NOT_READY);
            schemaReady=true;
        }
        String suffix=lock ? " FOR UPDATE NOWAIT" : "";
        // CTE phụ thuộc parent rồi câu → turn → submission; giữ NOWAIT, gom bốn lượt đọc thành một chuyến DB.
        try {
            var rows=jdbc.query("""
                WITH p AS MATERIALIZED (SELECT * FROM aives.oral_exam_progress WHERE student_exam_id=?%s),
                q AS MATERIALIZED (SELECT q.* FROM aives.oral_question_progress q,p WHERE q.student_exam_id=p.student_exam_id ORDER BY q.question_order%s),
                t AS MATERIALIZED (SELECT t.* FROM aives.oral_exam_turns t,p WHERE t.student_exam_id=p.student_exam_id
                    AND (SELECT count(*) FROM q)>=0 ORDER BY t.created_at,t.id%s),
                s AS MATERIALIZED (SELECT s.* FROM aives.oral_answer_submissions s,p WHERE s.student_exam_id=p.student_exam_id
                    AND (SELECT count(*) FROM t)>=0 ORDER BY s.updated_at,s.id%s)
                SELECT to_jsonb(p) || jsonb_build_object(
                    'questions',COALESCE((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.question_order) FROM q),'[]'::jsonb),
                    'turns',COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.created_at,t.id) FROM t),'[]'::jsonb),
                    'submissions',COALESCE((SELECT jsonb_agg(to_jsonb(s) ORDER BY s.updated_at,s.id) FROM s),'[]'::jsonb)) AS payload FROM p
                """.formatted(suffix,lock ? " FOR UPDATE OF q NOWAIT" : "",lock ? " FOR UPDATE OF t NOWAIT" : "",lock ? " FOR UPDATE OF s NOWAIT" : ""),
                (row,index) -> json.readValue(row.getString("payload"),OralExamProgress.class),examId);
            if (rows.isEmpty()) return Optional.empty();
            return Optional.of(rows.getFirst());
        } catch (BadSqlGrammarException failure) {
            if ("42P01".equals(failure.getSQLException().getSQLState())) {
                schemaReady=false;
                throw new AppException(ErrorCode.ORAL_SCHEMA_NOT_READY);
            }
            throw failure;
        }
    }

    /** Một UPDATE đúng client; không phụ thuộc revision/turn vốn thay đổi khi worker chuyển bước. */
    @Override
    public boolean renewLease(long examId, UUID client, OffsetDateTime now, OffsetDateTime until) {
        return jdbc.update("UPDATE aives.oral_exam_progress p SET lease_until=? FROM aives.student_exams e,aives.exam_sessions s WHERE p.student_exam_id=? AND p.client_session_id=? AND p.phase NOT IN ('COMPLETED','EXPIRED') AND e.id=p.student_exam_id AND e.session_id=s.id AND e.status='IN_PROGRESS' AND s.status='ONGOING' AND e.scheduled_start_at<=? AND e.scheduled_end_at>?",until,examId,client,now,now)==1;
    }

    /** Đọc trạng thái cuối sau UPDATE lease thất bại, không khóa hoặc tải cả hội thoại. */
    @Override
    public boolean leaseEnded(long examId, OffsetDateTime now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM aives.student_exams e
                  LEFT JOIN aives.oral_exam_progress p ON p.student_exam_id=e.id
                  WHERE e.id=? AND (e.status IN ('COMPLETED','EXPIRED')
                    OR p.phase IN ('COMPLETED','EXPIRED') OR e.scheduled_end_at<=?))
                """, Boolean.class, examId, now));
    }

    /** Tạo parent trước khi tạo các dòng câu con; currentTurnId chưa có FK vòng. */
    @Override
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
    @Override
    public void save(OralExamProgress p) {
        // Một statement nguyên tử, chỉ UPDATE đúng các cột trước đây; không ghi đè claim/audio/snapshot.
        jdbc.queryForObject("""
                WITH input AS (SELECT CAST(? AS jsonb) AS data),
                p AS (UPDATE aives.oral_exam_progress p SET phase=v.phase,recovery_phase=v.recovery_phase,
                    current_question_order=v.current_question_order,current_turn_id=v.current_turn_id,follow_ups_used=v.follow_ups_used,
                    answer_opened_at=v.answer_opened_at,client_session_id=v.client_session_id,lease_until=v.lease_until,revision=v.revision,updated_at=v.updated_at
                    FROM input i,LATERAL jsonb_populate_record(NULL::aives.oral_exam_progress,i.data) v
                    WHERE p.student_exam_id=v.student_exam_id RETURNING p.student_exam_id),
                q AS (UPDATE aives.oral_question_progress q SET status=v.status,answer_used_ms=v.answer_used_ms,outcome_reason=v.outcome_reason
                    FROM input i,LATERAL jsonb_populate_recordset(NULL::aives.oral_question_progress,i.data->'questions') v
                    WHERE q.student_exam_question_id=v.student_exam_question_id AND q.student_exam_id=(i.data->>'student_exam_id')::bigint RETURNING q.student_exam_question_id),
                t AS (UPDATE aives.oral_exam_turns t SET language=v.language,prompt_ready=v.prompt_ready,answer_status=v.answer_status,answer_text=v.answer_text,answered_at=v.answered_at
                    FROM input i,LATERAL jsonb_populate_recordset(NULL::aives.oral_exam_turns,i.data->'turns') v
                    WHERE t.id=v.id AND t.student_exam_id=(i.data->>'student_exam_id')::bigint RETURNING t.id),
                s AS (UPDATE aives.oral_answer_submissions s SET status=v.status,transcript_text=v.transcript_text,decision_kind=v.decision_kind,decision_text=v.decision_text,updated_at=v.updated_at
                    FROM input i,LATERAL jsonb_populate_recordset(NULL::aives.oral_answer_submissions,i.data->'submissions') v
                    WHERE s.id=v.id AND s.student_exam_id=(i.data->>'student_exam_id')::bigint RETURNING s.id)
                SELECT (SELECT count(*) FROM p)+(SELECT count(*) FROM q)+(SELECT count(*) FROM t)+(SELECT count(*) FROM s)
                """,Long.class,json.writeValueAsString(p));
    }

    /** Tạo lượt hỏi một lần và lưu lời AI vào transcripts với thứ tự do BE cấp. */
    @Override
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
    @Override
    public void insertSubmission(OralExamProgress.Submission s) {
        jdbc.update("INSERT INTO aives.oral_answer_submissions(id,student_exam_id,turn_id,status,updated_at) VALUES (?,?,?,?,?)",
                s.getId(), s.getStudentExamId(), s.getTurnId(), s.getStatus(), s.getUpdatedAt());
    }

    /** Chỉ callback STT nội bộ ghi bằng chứng có nội dung, đúng một lần cho mỗi lượt hỏi. */
    @Override
    public void insertAnswer(OralExamProgress.Turn turn, UUID submissionId) {
        jdbc.update("""
                INSERT INTO aives.transcripts(student_exam_question_id,turn_no,turn_type,content,created_at,oral_turn_id,oral_submission_id)
                VALUES (?,?,'STUDENT_ANS',?,?,?,?)
                """, turn.getStudentExamQuestionId(), turn.getFollowUpIndex() * 2 + 2, turn.getAnswerText(), turn.getAnsweredAt(), turn.getId(), submissionId);
    }

    /** Không tự nhận dữ liệu legacy đã có hội thoại là dữ liệu của bộ điều phối mới. */
    @Override
    public boolean hasLegacyConversation(long examId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM aives.transcripts t JOIN aives.student_exam_questions q ON q.id=t.student_exam_question_id WHERE q.student_exam_id=?)
                """, Boolean.class, examId));
    }

    /** Cùng requestId chỉ hợp lệ với nguyên payload ban đầu. */
    @Override
    public Optional<String> commandHash(long examId, UUID requestId) {
        return jdbc.query("SELECT request_hash FROM aives.oral_exam_commands WHERE student_exam_id=? AND request_id=?",
                (row, index) -> row.getString(1), examId, requestId).stream().findFirst();
    }

    /** Receipt và thay đổi nghiệp vụ commit cùng transaction. */
    @Override
    public void rememberCommand(long examId, UUID requestId, String hash, OffsetDateTime now) {
        jdbc.update("INSERT INTO aives.oral_exam_commands(student_exam_id,request_id,request_hash,created_at) VALUES (?,?,?,?)", examId, requestId, hash, now);
    }

    /** Enum nullable chỉ xuất tên cố định, không ghép giá trị vào SQL. */
    private String name(Enum<?> value) { return value == null ? null : value.name(); }
}
