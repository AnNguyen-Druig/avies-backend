package com.avies.backend.exam;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.avies.backend.exam.ExamFoundationPostgresConfig.*;
import static org.assertj.core.api.Assertions.*;

/** Chạy file SQL thật trên database tạm riêng từng ca; kiểm tra cả nâng cấp và khởi tạo mới. */
class Feature2MigrationPostgresIT {
    /** Container riêng cho migration test, không lấy cấu hình kết nối từ môi trường. */
    private static PostgreSQLContainer postgres;

    /** Thiếu Docker làm test thất bại rõ ràng, không âm thầm skip. */
    @BeforeAll
    static void start() {
        postgres = newPostgres();
        postgres.start();
    }

    /** Dọn duy nhất container được lớp test này tạo. */
    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    /** Nâng cấp lặp vẫn giữ mọi bản ghi/cột/constraint/index cũ, không tạo lịch giả. */
    @Test
    void migrationPreservesDataAndObjectsAndCanRunTwice() throws Exception {
        inDatabase(connection -> {
            legacySchema(connection);
            List<String> constraints = rows(connection, "SELECT conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conrelid IN ('aives.exam_sessions'::regclass,'aives.student_exams'::regclass,'aives.student_exam_questions'::regclass) ORDER BY conname");
            assertThat(constraints).hasSize(20);
            String exam = rows(connection, "SELECT to_jsonb(e)::text FROM aives.exam_sessions e").getFirst();
            String student = rows(connection, "SELECT to_jsonb(e)::text FROM aives.student_exams e").getFirst();
            String link = rows(connection, "SELECT to_jsonb(e)::text FROM aives.student_exam_questions e").getFirst();
            String migration = projectSql("docs/migrations/20261016_feature2_exam_foundation.sql");
            execute(connection, migration);
            execute(connection, migration);
            assertThat(rows(connection, "SELECT conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conrelid IN ('aives.exam_sessions'::regclass,'aives.student_exams'::regclass,'aives.student_exam_questions'::regclass) ORDER BY conname"))
                    .containsAll(constraints).hasSize(25);
            assertThat(rows(connection, "SELECT (to_jsonb(e)-ARRAY['scheduled_start_at','scheduled_end_at','slot_duration_minutes'])::text FROM aives.exam_sessions e").getFirst()).isEqualTo(exam);
            assertThat(rows(connection, "SELECT (to_jsonb(e)-ARRAY['slot_order','scheduled_start_at','scheduled_end_at'])::text FROM aives.student_exams e").getFirst()).isEqualTo(student);
            assertThat(rows(connection, "SELECT to_jsonb(e)::text FROM aives.student_exam_questions e").getFirst()).isEqualTo(link);
            assertThat(rows(connection, "SELECT count(*)::text FROM aives.student_exams WHERE slot_order IS NULL AND scheduled_start_at IS NULL AND scheduled_end_at IS NULL")).containsExactly("1");
            assertVerificationPassesReadOnly(connection);
        });
    }

    /** SQL baseline hiện hành đạt invariant; chạy lại migration lifecycle mới, không sửa/chạy ngược migration lịch sử. */
    @Test
    void freshInitializationMatchesVerification() throws Exception {
        inDatabase(connection -> {
            execute(connection, projectSql("docs/database/AIVES_DB_POSTGRESQL_FINAL.sql"));
            assertVerificationPassesReadOnly(connection);
            execute(connection, projectSql("docs/migrations/20261025_feature2_exam_lifecycle.sql"));
            assertVerificationPassesReadOnly(connection);
        });
    }

    /** Đối tượng trùng tên nhưng sai định nghĩa phải chặn COMMIT, không tự sửa hoặc bỏ qua. */
    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE aives.exam_sessions ADD COLUMN scheduled_start_at timestamp",
            "ALTER TABLE aives.student_exams ADD COLUMN slot_order bigint",
            "ALTER TABLE aives.student_exams ADD COLUMN slot_order integer NOT NULL DEFAULT 1",
            "ALTER TABLE aives.exam_sessions ADD COLUMN scheduled_start_at timestamptz DEFAULT now()",
            "ALTER TABLE aives.exam_sessions ADD CONSTRAINT ck_exam_session_schedule CHECK (true)",
            "ALTER TABLE aives.exam_sessions ADD COLUMN slot_duration_minutes integer; ALTER TABLE aives.exam_sessions ADD CONSTRAINT ck_exam_session_slot_duration CHECK (slot_duration_minutes IS NULL OR slot_duration_minutes > 0) NOT VALID",
            "ALTER TABLE aives.student_exams ADD CONSTRAINT uq_student_exam_slot_order UNIQUE (student_id)",
            "ALTER TABLE aives.student_exams ADD COLUMN slot_order integer; ALTER TABLE aives.student_exams ADD CONSTRAINT uq_student_exam_slot_order UNIQUE NULLS NOT DISTINCT (session_id,slot_order)",
            "ALTER TABLE aives.student_exams DROP CONSTRAINT uq_exam_session_student",
            "ALTER TABLE aives.student_exams DROP CONSTRAINT student_exams_session_id_fkey; ALTER TABLE aives.student_exams ADD CONSTRAINT student_exams_session_id_fkey FOREIGN KEY (session_id) REFERENCES aives.exam_sessions(id) ON UPDATE CASCADE ON DELETE RESTRICT",
            "ALTER TABLE aives.exam_sessions DROP CONSTRAINT exam_sessions_max_main_questions_check; ALTER TABLE aives.exam_sessions ADD CONSTRAINT exam_sessions_max_main_questions_check CHECK (max_main_questions >= 0)",
            "DROP INDEX aives.idx_student_exams_student_id; CREATE INDEX idx_student_exams_student_id ON aives.student_exams(status)",
            "ALTER TABLE aives.student_exams ALTER COLUMN ai_feedback TYPE varchar(50)"
    })
    void refusesSchemaDriftAndRollsBackAllAdditions(String drift) throws Exception {
        inDatabase(connection -> {
            legacySchema(connection);
            execute(connection, drift);
            List<String> before = rows(connection, "SELECT table_name || '.' || column_name || ':' || data_type FROM information_schema.columns WHERE table_schema='aives' ORDER BY table_name,ordinal_position");
            assertThatThrownBy(() -> execute(connection, projectSql("docs/migrations/20261016_feature2_exam_foundation.sql")))
                    .isInstanceOf(SQLException.class);
            execute(connection, "ROLLBACK");
            assertThat(rows(connection, "SELECT table_name || '.' || column_name || ':' || data_type FROM information_schema.columns WHERE table_schema='aives' ORDER BY table_name,ordinal_position"))
                    .isEqualTo(before);
            assertThat(rows(connection, "SELECT count(*)::text FROM aives.student_exams")).containsExactly("1");
        });
    }

    /** Một cặp thời gian nửa vời đã tồn tại phải làm migration thất bại và giữ nguyên dữ liệu đó. */
    @Test
    void rejectsInvalidExistingSchedulingDataWithoutBackfill() throws Exception {
        inDatabase(connection -> {
            legacySchema(connection);
            execute(connection, "ALTER TABLE aives.exam_sessions ADD COLUMN scheduled_start_at timestamptz; UPDATE aives.exam_sessions SET scheduled_start_at='2026-11-01T01:00:00Z'");
            assertThatThrownBy(() -> execute(connection, projectSql("docs/migrations/20261016_feature2_exam_foundation.sql")))
                    .isInstanceOf(SQLException.class);
            execute(connection, "ROLLBACK");
            assertThat(rows(connection, "SELECT count(*)::text FROM aives.exam_sessions WHERE scheduled_start_at IS NOT NULL")).containsExactly("1");
            assertThat(rows(connection, "SELECT count(*)::text FROM information_schema.columns WHERE table_schema='aives' AND table_name='exam_sessions' AND column_name='scheduled_end_at'")).containsExactly("0");
        });
    }

    /** Verification phải FAIL khi CHECK bị làm yếu dù giữ nguyên tên. */
    @Test
    void verificationDetectsWeakenedChecksAndWrongForeignKeyAction() throws Exception {
        inDatabase(connection -> {
            legacySchema(connection);
            execute(connection, projectSql("docs/migrations/20261016_feature2_exam_foundation.sql"));
            execute(connection, "ALTER TABLE aives.exam_sessions DROP CONSTRAINT ck_exam_session_schedule; ALTER TABLE aives.exam_sessions ADD CONSTRAINT ck_exam_session_schedule CHECK (true)");
            execute(connection, "ALTER TABLE aives.student_exam_questions DROP CONSTRAINT student_exam_questions_question_id_fkey; ALTER TABLE aives.student_exam_questions ADD CONSTRAINT student_exam_questions_question_id_fkey FOREIGN KEY (question_id) REFERENCES aives.questions(id) ON UPDATE CASCADE ON DELETE CASCADE");
            assertThat(verificationFailures(connection)).contains("validated CHECK definition: ck_exam_session_schedule",
                    "PK/FK/UNIQUE: student_exam_questions_question_id_fkey");
        });
    }

    /** Verifier chỉ nhận hai bộ trạng thái đã phê duyệt, không bỏ qua CHECK hoặc nhận trạng thái lạ. */
    @ParameterizedTest
    @ValueSource(strings = {"CHECK (true)", "CHECK (status IN ('ASSIGNED','IN_PROGRESS','COMPLETED','UNKNOWN'))"})
    void verificationRejectsUnexpectedLifecycleStatusDefinitions(String definition) throws Exception {
        inDatabase(connection -> {
            execute(connection, projectSql("docs/database/AIVES_DB_POSTGRESQL_FINAL.sql"));
            execute(connection, "ALTER TABLE aives.student_exams DROP CONSTRAINT student_exams_status_check; "
                    + "ALTER TABLE aives.student_exams ADD CONSTRAINT student_exams_status_check " + definition);
            assertThat(verificationFailures(connection)).contains("validated CHECK definition: student_exams_status_check");
        });
    }

    /** Verification đọc schema thiếu phải trả FAIL thay vì PASS nhờ tên đối tượng trùng. */
    @Test
    void verificationOnEmptyDatabaseReturnsFailures() throws Exception {
        inDatabase(connection -> assertThat(verificationFailures(connection)).isNotEmpty());
    }

    /** Chạy verifier trong transaction READ ONLY để PostgreSQL tự chặn tác dụng phụ nếu có. */
    private void assertVerificationPassesReadOnly(Connection connection) throws Exception {
        execute(connection, "BEGIN READ ONLY");
        try {
            assertThat(verificationFailures(connection)).isEmpty();
        } finally {
            execute(connection, "ROLLBACK");
        }
    }

    /** Thu thập tên invariant thất bại từ chính file kiểm tra dành cho Supabase. */
    private List<String> verificationFailures(Connection connection) throws Exception {
        List<String> failures = new ArrayList<>();
        try (var statement = connection.createStatement();
             var results = statement.executeQuery(projectSql("docs/migrations/feature2_post_migration_check.sql"))) {
            int total = 0;
            while (results.next()) {
                total++;
                if (!results.getBoolean("passed")) failures.add(results.getString("check_name"));
            }
            assertThat(total).isGreaterThan(50);
        }
        return failures;
    }

    /** Đọc một cột dạng text để so sánh snapshot dữ liệu và metadata trước/sau migration. */
    private List<String> rows(Connection connection, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (var statement = connection.createStatement(); var results = statement.executeQuery(sql)) {
            while (results.next()) rows.add(results.getString(1));
        }
        return rows;
    }

    /** Tạo/xóa database tên UUID trong đúng container sở hữu; không có đường nhận URL Supabase. */
    private void inDatabase(DatabaseCheck check) throws Exception {
        String name = "feature2_case_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection control = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            execute(control, "CREATE DATABASE " + name);
            try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl().replace("/feature2_it", "/" + name),
                    postgres.getUsername(), postgres.getPassword())) {
                check.run(connection);
            } finally {
                if (!name.matches("feature2_case_[0-9a-f]{32}")) throw new IllegalStateException("Tên database test không hợp lệ");
                execute(control, "DROP DATABASE " + name);
            }
        }
    }

    /** Callback chỉ được cấp connection của database tạm vừa tạo. */
    @FunctionalInterface
    private interface DatabaseCheck {
        /** Thực hiện một ca kiểm tra SQL và truyền lỗi về JUnit. */
        void run(Connection connection) throws Exception;
    }
}
