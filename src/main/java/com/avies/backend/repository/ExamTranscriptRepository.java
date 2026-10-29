package com.avies.backend.repository;

import com.avies.backend.dto.projection.ExamAnswerEvidenceRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Đọc bằng chứng Feature 3 trên bảng transcripts hiện có, không tạo bản ghi hoặc giả lập câu trả lời. */
@Repository
public class ExamTranscriptRepository {
    /** JDBC tham gia cùng transaction/datasource với JPA. */
    private final JdbcTemplate jdbc;

    /** Dùng datasource do Spring quản lý, không đọc cấu hình hoặc mở database ngoài. */
    public ExamTranscriptRepository(DataSource source) {
        this.jdbc = new JdbcTemplate(source);
    }

    /** Caller khóa parent, child và bộ câu trước; khóa bằng chứng để không bị sửa/xóa trong lúc Finish. */
    public List<ExamAnswerEvidenceRow> lockSavedAnswers(Long examId) {
        return jdbc.query("""
                SELECT t.student_exam_question_id,t.content,t.created_at
                FROM aives.transcripts t
                JOIN aives.student_exam_questions q ON q.id=t.student_exam_question_id
                WHERE q.student_exam_id=? AND t.turn_type='STUDENT_ANS'
                ORDER BY t.id FOR SHARE OF t NOWAIT
                """, (row, index) -> new ExamAnswerEvidenceRow(row.getLong("student_exam_question_id"),
                row.getString("content"), row.getObject("created_at", OffsetDateTime.class)), examId);
    }

    /** Có lượt trả lời là đã có bằng chứng; không tự ghi vắng cho trạng thái ASSIGNED bị lệch dữ liệu. */
    public boolean hasStudentAnswer(Long examId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM aives.transcripts t
                JOIN aives.student_exam_questions q ON q.id=t.student_exam_question_id
                WHERE q.student_exam_id=? AND t.turn_type='STUDENT_ANS')
                """, Boolean.class, examId));
    }

    /** Giữ đề oral kín kể cả khi quản trị viên tạm tắt AI; caller đã khóa lượt thi. */
    public boolean hasOralProgress(Long examId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('aives.oral_exam_progress') IS NOT NULL",Boolean.class))) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM aives.oral_exam_progress WHERE student_exam_id=?)",Boolean.class,examId));
    }

    /** Lượt mới phải đủ kết quả của bộ điều phối; chỉ lượt không có progress mới dùng quy tắc legacy. */
    public Optional<Boolean> oralCompletionState(Long examId, OffsetDateTime timestamp) {
        // Cho phép deployment code trước khi admin áp dụng migration; không tự tạo bảng hoặc dữ liệu.
        if (jdbc.queryForObject("SELECT to_regclass('aives.oral_exam_progress') IS NOT NULL", Boolean.class) != Boolean.TRUE) return Optional.empty();
        return jdbc.query("""
                SELECT p.phase='COMPLETED'
                    AND p.main_count=(SELECT count(*) FROM aives.oral_question_progress q WHERE q.student_exam_id=p.student_exam_id)
                    AND NOT EXISTS(SELECT 1 FROM aives.oral_question_progress q WHERE q.student_exam_id=p.student_exam_id
                        AND (q.status NOT IN ('ANSWERED','NO_ANSWER') OR (q.status='NO_ANSWER' AND (q.outcome_reason IS NULL OR q.outcome_reason NOT IN ('ANSWER_TIME_EXHAUSTED','SUBMITTED_UNANSWERED')))))
                    AND NOT EXISTS(SELECT 1 FROM aives.oral_exam_turns t WHERE t.student_exam_id=p.student_exam_id AND t.answer_status NOT IN ('ANSWERED','NO_ANSWER'))
                    AND NOT EXISTS(SELECT 1 FROM aives.oral_answer_submissions s WHERE s.student_exam_id=p.student_exam_id AND s.status IN ('PROCESSING','TRANSCRIBED','FAILED'))
                    AND NOT EXISTS(SELECT 1 FROM aives.oral_question_progress q WHERE q.student_exam_id=p.student_exam_id AND q.status='ANSWERED'
                        AND NOT EXISTS(SELECT 1 FROM aives.oral_exam_turns ot JOIN aives.transcripts t ON t.oral_turn_id=ot.id
                            JOIN aives.student_exams e ON e.id=ot.student_exam_id
                            WHERE ot.student_exam_question_id=q.student_exam_question_id AND ot.follow_up_index=0 AND ot.answer_status='ANSWERED'
                                AND t.turn_type='STUDENT_ANS' AND t.content=ot.answer_text AND length(btrim(t.content))>0
                                AND t.created_at>=e.started_at AND t.created_at<=?))
                FROM aives.oral_exam_progress p WHERE p.student_exam_id=?
                """, (row, index) -> row.getBoolean(1), timestamp, examId).stream().findFirst();
    }
}
