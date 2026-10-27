package com.avies.backend.repository;

import com.avies.backend.dto.projection.ExamAnswerEvidenceRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;

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
}
