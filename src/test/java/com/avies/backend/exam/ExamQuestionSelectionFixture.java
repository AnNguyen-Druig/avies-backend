package com.avies.backend.exam;

import org.springframework.jdbc.core.JdbcTemplate;

/** SQL chỉ chạy trên datasource Testcontainers được truyền rõ ràng, không đọc URL ngoài. */
final class ExamQuestionSelectionFixture {
    /** JDBC của container do context test sở hữu. */
    final JdbcTemplate jdbc;
    /** Fixture parent/roles/subjects đã được test ở CRUD trước. */
    final ExamSessionFixture base;

    /** Không tạo kết nối hoặc đọc application.yaml trong helper. */
    ExamQuestionSelectionFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.base = new ExamSessionFixture(jdbc);
    }

    /** Reset dataset cô lập và tạo thêm hai STUDENT dùng thử chống lặp. */
    void reset() {
        base.reset();
        jdbc.update("INSERT INTO aives.users(role_id,username,email,full_name) VALUES (3,'student2','s2@test.invalid','S2'),(3,'student3','s3@test.invalid','S3')");
    }

    /** Parent cần N câu, không chuyển sang ONGOING như Start workflow tương lai. */
    long session(int count) {
        long id = base.session(1, 1, "UPCOMING");
        jdbc.update("UPDATE aives.exam_sessions SET max_main_questions=? WHERE id=?", count, id);
        return id;
    }

    /** Child ASSIGNED có lịch slot hợp lệ để selector không chế tạo dữ liệu thời gian. */
    long exam(long parent, long studentId, int slot) {
        return jdbc.queryForObject("INSERT INTO aives.student_exams(session_id,student_id,status,slot_order,scheduled_start_at,scheduled_end_at) VALUES (?,?,'ASSIGNED',?,?,?) RETURNING id",
                Long.class, parent, studentId, slot, base.start.plusMinutes((slot - 1L) * 20), base.start.plusMinutes(slot * 20L));
    }

    /** Câu hợp lệ có rubric 4+6, đáp án được gắn secret để kiểm tra không lọt DTO selection. */
    long question(long subject, String status, String bloom) {
        long id = jdbc.queryForObject("INSERT INTO aives.questions(subject_id,created_by,content,status,bloom_level,reference_answer,is_approved) VALUES (?,1,'Question body',?,?, 'PRIVATE_REFERENCE_ANSWER',?) RETURNING id",
                Long.class, subject, status, bloom, status.equals("APPROVED"));
        if (jdbc.queryForObject("SELECT count(*) FROM aives.rubrics WHERE subject_id=?", Integer.class, subject) == 0) {
            long rubric = jdbc.queryForObject("INSERT INTO aives.rubrics(subject_id,title,description) VALUES (?,'PRIVATE_RUBRIC','Description') RETURNING id", Long.class, subject);
            jdbc.update("INSERT INTO aives.rubric_criteria(rubric_id,criterion_text,point_weight,display_order) VALUES (?,'Criterion A',4,1),(?,'Criterion B',6,2)", rubric, rubric);
        }
        return id;
    }

    /** Câu cùng môn đã duyệt, mặc định Bloom UNDERSTAND. */
    long question() { return question(1, "APPROVED", "UNDERSTAND"); }

    /** Liên kết lịch sử hoặc bộ legacy phải dùng FK/UNIQUE thật như production. */
    long link(long examId, long questionId, int order) {
        freezeRubricForHistory(examId);
        return jdbc.queryForObject("""
                INSERT INTO aives.student_exam_questions(student_exam_id,question_id,question_order,
                    snapshot_content,snapshot_reference_answer,snapshot_bloom_level,
                    snapshot_answer_time_limit_seconds,snapshot_question_version,snapshot_captured_at)
                SELECT ?,q.id,?,q.content,q.reference_answer,q.bloom_level,q.answer_time_limit_seconds,q.version,now()
                FROM aives.questions q WHERE q.id=? RETURNING id
                """,
                Long.class, examId, order, questionId);
    }

    /** Tạo bằng chứng lịch sử xác định cho fixture, không dùng để backfill trong production. */
    private void freezeRubricForHistory(long examId) {
        jdbc.update("""
                UPDATE aives.exam_sessions s SET rubric_snapshot=(
                    SELECT jsonb_build_object('schemaVersion',1,'sourceRubricId',r.id,'subjectId',r.subject_id,
                        'title',r.title,'description',r.description,'sourceUpdatedAt',NULL,
                        'criteria',jsonb_agg(jsonb_build_object('sourceCriterionId',c.id,'text',c.criterion_text,
                            'maxScore',c.point_weight,'mandatory',c.is_mandatory,'displayOrder',c.display_order)
                            ORDER BY c.display_order))::text
                    FROM aives.rubrics r JOIN aives.rubric_criteria c ON c.rubric_id=r.id
                    WHERE r.subject_id=s.subject_id GROUP BY r.id),rubric_snapshot_at=now()
                WHERE s.id=(SELECT session_id FROM aives.student_exams WHERE id=?) AND s.rubric_snapshot IS NULL
                """, examId);
    }
}
