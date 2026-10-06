package com.avies.backend.question;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Date;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Kiểm thử migration và luồng MVC → JWT thật → service → PostgreSQL thật, không mock nghiệp vụ. */
@SpringJUnitConfig(QuestionPostgresConfig.class)
@WebAppConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "jwt.signerKey=question-integration-test-key-only-never-use-in-production-0123456789abcdef",
        "jwt.valid-duration=3600", "jwt.refreshable-duration=7200"
})
class QuestionPostgresIT {
    /** Context web tối thiểu gồm controller, filter bảo mật và nghiệp vụ thật. */
    @Autowired private WebApplicationContext context;
    /** Datasource chỉ được lấy từ container do bộ test tạo. */
    @Autowired private DataSource dataSource;
    /** Container riêng dùng thêm database độc lập cho các kịch bản migration. */
    @Autowired private PostgreSQLContainer postgres;
    /** Bộ mã hóa mật khẩu thật dùng tạo tài khoản test. */
    @Autowired private PasswordEncoder passwordEncoder;
    /** Bộ gửi request qua DispatcherServlet và Spring Security, không mở cổng HTTP. */
    private MockMvc mvc;
    /** JDBC dùng kiểm tra dữ liệu đã commit sau request. */
    private JdbcTemplate jdbc;

    /** Làm sạch duy nhất database question_it trong container và tạo fixture xác định cho từng test. */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE aives.question_audit_logs, aives.invalidated_tokens, aives.roles, aives.subjects RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO aives.roles(code,name) VALUES ('ADMIN','Admin'),('LECTURER','Lecturer'),('STUDENT','Student')");
        String hash = passwordEncoder.encode("test-password");
        for (String username : List.of("admin", "owner", "colleague", "outsider", "student")) {
            String role = username.equals("admin") ? "ADMIN" : username.equals("student") ? "STUDENT" : "LECTURER";
            jdbc.update("""
                    INSERT INTO aives.users(role_id,username,email,password_hash,full_name)
                    SELECT id,?,?,?,? FROM aives.roles WHERE code=?
                    """, username, username + "@test.invalid", hash, "Tài khoản " + username, role);
        }
        jdbc.update("INSERT INTO aives.subjects(code,name) VALUES ('SWD392','Thiết kế phần mềm'),('OTHER','Môn khác')");
        jdbc.update("""
                INSERT INTO aives.lecturer_subject_assignments(lecturer_id,subject_id)
                SELECT id,1 FROM aives.users WHERE username IN ('owner','colleague')
                """);
    }

    /** Chạy đủ tạo, đọc, lọc, sửa, xóa bằng JWT lấy từ API đăng nhập và kiểm tra audit sau commit. */
    @Test
    void signedLoginAndCompleteCrudRoundTrip() throws Exception {
        String token = login("owner");
        long id = create(token);
        mvc.perform(get("/questions/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content").value("Giải thích HashTable."));
        mvc.perform(get("/questions").param("topic", "array").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalElements").value(1));
        mvc.perform(patch("/questions/" + id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"content":"Nội dung mới","referenceAnswer":null,"topics":["HashTable"]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.version").value(1));
        mvc.perform(patch("/questions/" + id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"content\":\"Dữ liệu cũ\"}"))
                .andExpect(status().isConflict());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete("/questions/" + id).header("Authorization", bearer(token))).andExpect(status().isOk());
        }
        mvc.perform(get("/questions/" + id).header("Authorization", bearer(token))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.question_topics", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT action FROM aives.question_audit_logs ORDER BY id", String.class))
                .containsExactly("CREATED", "UPDATED", "DELETED");
    }

    /** Kiểm tra quyền thực tế: JWT hợp lệ vẫn không cho sinh viên hoặc giảng viên ngoài phạm vi truy cập. */
    @Test
    void enforcesRolesOwnershipAndAssignmentsWithRealTokens() throws Exception {
        String owner = login("owner");
        long id = create(owner);
        mvc.perform(get("/questions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/questions").header("Authorization", bearer(login("student"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/questions/" + id).header("Authorization", bearer(login("colleague"))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/questions").header("Authorization", bearer(login("outsider")))
                        .contentType(MediaType.APPLICATION_JSON).content(creation()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/questions/" + id).header("Authorization", bearer(login("admin"))))
                .andExpect(status().isOk());
        jdbc.update("UPDATE aives.questions SET status='APPROVED',is_approved=true WHERE id=?", id);
        mvc.perform(get("/questions/" + id).header("Authorization", bearer(login("colleague"))))
                .andExpect(status().isOk());
        mvc.perform(delete("/questions/" + id).header("Authorization", bearer(login("admin"))))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE aives.users SET role_id=(SELECT id FROM aives.roles WHERE code='STUDENT') WHERE username='owner'");
        mvc.perform(get("/questions/" + id).header("Authorization", bearer(owner))).andExpect(status().isForbidden());
    }

    /** Chữ ký sai phải bị từ chối bởi decoder thật thay vì ném lỗi dịch vụ xác thực. */
    @Test
    void rejectsTamperedJwt() throws Exception {
        String token = login("owner");
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
        mvc.perform(get("/questions").header("Authorization", bearer(tampered))).andExpect(status().isUnauthorized());
    }

    /** JWT đã đăng xuất phải bị từ chối khi ID token đã có trong blacklist PostgreSQL. */
    @Test
    void rejectsRevokedJwt() throws Exception {
        String token = login("owner");
        mvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andExpect(status().isOk());
        mvc.perform(get("/questions").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
    }

    /** JWT có chữ ký hợp lệ nhưng hết hạn vẫn phải bị từ chối, không được bỏ qua kiểm tra thời gian. */
    @Test
    void rejectsExpiredSignedJwt() throws Exception {
        SignedJWT expired = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), new JWTClaimsSet.Builder()
                .subject("owner").issuer("aives.com").jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                .claim("scope", "ROLE_LECTURER").build());
        expired.sign(new MACSigner("question-integration-test-key-only-never-use-in-production-0123456789abcdef"));
        mvc.perform(get("/questions").header("Authorization", bearer(expired.serialize())))
                .andExpect(status().isUnauthorized());
    }

    /** Dữ liệu sai và mass assignment không được đi tới ghi database; PATCH giữ nguyên phải không tạo audit. */
    @Test
    void validatesRequestsAndAvoidsNoOpWrites() throws Exception {
        String token = login("owner");
        for (String body : List.of("{\"subjectId\":1,\"content\":\"\"}",
                "{\"subjectId\":1,\"content\":\"Câu hỏi\",\"status\":\"APPROVED\"}",
                "{\"subjectId\":1,\"content\":\"Câu hỏi\",\"answerTimeLimitSeconds\":-1}")) {
            mvc.perform(post("/questions").header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.questions", Long.class)).isZero();
        long id = create(token);
        mvc.perform(patch("/questions/" + id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"content\":\"Giải thích HashTable.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.version").value(0));
        mvc.perform(get("/questions").param("size", "101").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.question_audit_logs", Long.class)).isEqualTo(1L);
    }

    /** Hai PATCH cùng version qua MVC phải chỉ có một lần commit trên PostgreSQL. */
    @Test
    void serializesConcurrentHttpUpdates() throws Exception {
        String token = login("owner");
        long id = create(token);
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> update = () -> {
                start.await(10, TimeUnit.SECONDS);
                return mvc.perform(patch("/questions/" + id).header("Authorization", bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":0,\"content\":\"" + UUID.randomUUID() + "\"}"))
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> first = executor.submit(update);
            Future<Integer> second = executor.submit(update);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM aives.questions WHERE id=?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.question_audit_logs", Long.class)).isEqualTo(2L);
    }

    /** Hai DELETE cạnh tranh không tạo hai sự kiện xóa hoặc trả lỗi do race condition. */
    @Test
    void concurrentHttpDeletesAreIdempotent() throws Exception {
        String token = login("owner");
        long id = create(token);
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> deletion = () -> {
                start.await(10, TimeUnit.SECONDS);
                return mvc.perform(delete("/questions/" + id).header("Authorization", bearer(token)))
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> first = executor.submit(deletion);
            Future<Integer> second = executor.submit(deletion);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsOnly(200);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.question_audit_logs WHERE action='DELETED'", Long.class)).isEqualTo(1L);
    }

    /** Lỗi ghi audit ở database phải rollback cả câu hỏi và topics, không chỉ trả HTTP lỗi. */
    @Test
    void rollsBackEntireRequestWhenDatabaseRejectsAudit() throws Exception {
        jdbc.execute("ALTER TABLE aives.question_audit_logs ADD CONSTRAINT test_reject_created CHECK(action <> 'CREATED')");
        try {
            mvc.perform(post("/questions").header("Authorization", bearer(login("owner")))
                    .contentType(MediaType.APPLICATION_JSON).content(creation())).andExpect(status().isConflict());
            for (String table : List.of("questions", "question_topics", "question_audit_logs")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM aives." + table, Long.class)).isZero();
            }
        } finally {
            jdbc.execute("ALTER TABLE aives.question_audit_logs DROP CONSTRAINT test_reject_created");
        }
    }

    /** Câu đã gán vào bài thi không được sửa hoặc xóa dù vẫn mang trạng thái bản nháp. */
    @Test
    void refusesChangesToExamReferencedQuestion() throws Exception {
        String token = login("owner");
        long id = create(token);
        jdbc.update("INSERT INTO aives.student_exam_questions(question_id) VALUES (?)", id);
        mvc.perform(delete("/questions/" + id).header("Authorization", bearer(token))).andExpect(status().isConflict());
        mvc.perform(patch("/questions/" + id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"content\":\"Thay đổi\"}"))
                .andExpect(status().isConflict());
    }

    /** Kiểm tra cấu trúc, index và CHECK/UNIQUE/FK thực sự được PostgreSQL thực thi. */
    @Test
    void verifiesMigrationMetadataAndDatabaseConstraints() throws Exception {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema='aives' AND table_name='questions'
                """, Long.class)).isEqualTo(16L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE conrelid='aives.questions'::regclass
                AND conname IN ('ck_question_bloom_mvp','ck_question_answer_seconds','ck_question_source','ck_question_status','ck_question_version')
                """, Long.class)).isEqualTo(5L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname='aives' AND indexname IN
                ('uq_question_topics_normalized','idx_assignment_subject','idx_question_audit_question','idx_question_subject_status','idx_question_creator')
                """, Long.class)).isEqualTo(5L);
        long id = create(login("owner"));
        assertSqlState("23514", "UPDATE aives.questions SET bloom_level='CREATE' WHERE id=" + id);
        assertSqlState("23514", "UPDATE aives.questions SET answer_time_limit_seconds=0 WHERE id=" + id);
        assertSqlState("23514", "UPDATE aives.questions SET source_type='OTHER' WHERE id=" + id);
        assertSqlState("23514", "UPDATE aives.questions SET status='OTHER' WHERE id=" + id);
        assertSqlState("23514", "UPDATE aives.questions SET version=-1 WHERE id=" + id);
        assertSqlState("23505", "INSERT INTO aives.question_topics VALUES (" + id + ",'ARRAY')");
        assertSqlState("23503", "INSERT INTO aives.lecturer_subject_assignments(lecturer_id,subject_id) VALUES (2,999999)");
        assertSqlState("23505", "INSERT INTO aives.lecturer_subject_assignments(lecturer_id,subject_id) VALUES (2,1)");
    }

    /** Kiểm tra RLS bằng role không có BYPASSRLS; không dùng superuser để kết luận RLS hoạt động. */
    @Test
    void rlsBlocksDirectClientReadsAndWrites() throws Exception {
        create(login("owner"));
        String role = "client_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection()) {
            QuestionPostgresConfig.execute(connection, "CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
            QuestionPostgresConfig.execute(connection, "GRANT USAGE ON SCHEMA aives TO " + role);
            QuestionPostgresConfig.execute(connection, "GRANT SELECT, INSERT ON aives.question_topics, aives.lecturer_subject_assignments, aives.question_audit_logs TO " + role);
            QuestionPostgresConfig.execute(connection, "GRANT USAGE ON ALL SEQUENCES IN SCHEMA aives TO " + role);
            QuestionPostgresConfig.execute(connection, "SET ROLE " + role);
            for (String table : List.of("question_topics", "lecturer_subject_assignments", "question_audit_logs")) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM aives." + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isZero();
                }
            }
            for (String sql : List.of("INSERT INTO aives.question_topics VALUES (1,'Secret')",
                    "INSERT INTO aives.lecturer_subject_assignments(lecturer_id,subject_id) VALUES (2,2)",
                    "INSERT INTO aives.question_audit_logs(question_id,actor_id,action) VALUES (1,2,'CREATED')")) {
                assertThatThrownBy(() -> QuestionPostgresConfig.execute(connection, sql))
                        .isInstanceOf(SQLException.class).extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("42501");
            }
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_tables WHERE schemaname='aives' AND rowsecurity
                AND tablename IN ('question_topics','lecturer_subject_assignments','question_audit_logs')
                """, Long.class)).isEqualTo(3L);
    }

    /** Migration giữ dữ liệu cũ, phân loại thận trọng và không ghi đè giá trị đã xác minh khi chạy lại. */
    @Test
    void migrationPreservesLegacyDataAndSupportsRerun() throws Exception {
        try (Connection connection = freshDatabase(true)) {
            seedLegacy(connection, "REMEMBER");
            QuestionPostgresConfig.execute(connection, "INSERT INTO aives.questions(subject_id,created_by,content,is_ai_generated,is_approved) VALUES (1,1,'Manual legacy',false,false)");
            QuestionPostgresConfig.execute(connection, QuestionPostgresConfig.migration());
            try (var rows = connection.createStatement().executeQuery("SELECT source_type,status,version,updated_at=created_at AS same_time FROM aives.questions WHERE id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("AI");
                assertThat(rows.getString(2)).isEqualTo("APPROVED");
                assertThat(rows.getLong(3)).isZero();
                assertThat(rows.getBoolean(4)).isTrue();
            }
            try (var rows = connection.createStatement().executeQuery("SELECT source_type,status FROM aives.questions WHERE id=2")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isNull();
                assertThat(rows.getString(2)).isNull();
            }
            QuestionPostgresConfig.execute(connection, "UPDATE aives.questions SET source_type='IMPORT',status='REJECTED' WHERE id=1");
            QuestionPostgresConfig.execute(connection, QuestionPostgresConfig.migration());
            try (var rows = connection.createStatement().executeQuery("SELECT content,source_type,status FROM aives.questions WHERE id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("Legacy preserved");
                assertThat(rows.getString(2)).isEqualTo("IMPORT");
                assertThat(rows.getString(3)).isEqualTo("REJECTED");
            }
        }
    }

    /** Bloom ngoài MVP làm migration thất bại và không để lại schema nâng cấp dở dang. */
    @Test
    void invalidLegacyBloomAbortsMigrationWithoutPartialChanges() throws Exception {
        try (Connection connection = freshDatabase(true)) {
            seedLegacy(connection, "EVALUATE");
            assertThatThrownBy(() -> QuestionPostgresConfig.execute(connection, QuestionPostgresConfig.migration()))
                    .isInstanceOf(SQLException.class);
            QuestionPostgresConfig.execute(connection, "ROLLBACK");
            try (var rows = connection.createStatement().executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='aives' AND table_name='questions'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(8);
            }
            try (var rows = connection.createStatement().executeQuery("SELECT bloom_level FROM aives.questions")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("EVALUATE");
            }
        }
    }

    /** Lỗi ở giữa migration phải rollback cả ALTER TABLE đã chạy trước đó, không chỉ lỗi precheck. */
    @Test
    void lateMigrationFailureRollsBackEarlierSchemaChanges() throws Exception {
        try (Connection connection = freshDatabase(true)) {
            seedLegacy(connection, "REMEMBER");
            QuestionPostgresConfig.execute(connection, """
                    CREATE TABLE aives.question_topics(question_id BIGINT, topic VARCHAR(100));
                    INSERT INTO aives.question_topics VALUES (1,'Array'),(1,'array');
                    """);
            assertThatThrownBy(() -> QuestionPostgresConfig.execute(connection, QuestionPostgresConfig.migration()))
                    .isInstanceOf(SQLException.class).extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23505");
            QuestionPostgresConfig.execute(connection, "ROLLBACK");
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='aives' AND table_name='questions'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(8);
            }
        }
    }

    /** Script bàn giao chỉ đọc phải chạy được và xác nhận đủ các đối tượng đã triển khai. */
    @Test
    void readOnlyPostMigrationChecklistPasses() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            assertThat(statement.execute(Files.readString(QuestionPostgresConfig.projectFile(
                    "docs/migrations/question_post_migration_check.sql"), StandardCharsets.UTF_8))).isTrue();
            int checks = 0;
            try (var rows = statement.getResultSet()) {
                while (rows.next()) {
                    assertThat(rows.getBoolean("passed")).as(rows.getString("check_name")).isTrue();
                    checks++;
                }
            }
            assertThat(checks).isEqualTo(29);
            connection.rollback();
        }
    }

    /** File SQL khởi tạo mới phải chạy được độc lập với pgvector và tạo đủ cột/RLS của Question. */
    @Test
    void canonicalInitializationCreatesQuestionFoundation() throws Exception {
        try (Connection connection = freshDatabase(false)) {
            QuestionPostgresConfig.execute(connection, Files.readString(
                    QuestionPostgresConfig.projectFile("docs/database/AIVES_DB_POSTGRESQL_FINAL.sql"), StandardCharsets.UTF_8));
            try (var rows = connection.createStatement().executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='aives' AND table_name='questions'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(16);
            }
            try (var rows = connection.createStatement().executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname='aives' AND rowsecurity AND tablename IN ('question_topics','lecturer_subject_assignments','question_audit_logs')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(3);
            }
        }
    }

    /** Đăng nhập bằng mật khẩu thật để lấy JWT do AuthenticationService của ứng dụng ký. */
    private String login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/auth/signin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.authenticated").value(true)).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.result.token");
    }

    /** Tạo bản nháp qua HTTP giả lập và trả ID từ JSON, không tự ghi câu hỏi bằng repository. */
    private long create(String token) throws Exception {
        MvcResult result = mvc.perform(post("/questions").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(creation()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.result.status").value("DRAFT"))
                .andExpect(jsonPath("$.result.sourceType").value("MANUAL")).andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.result.id");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/questions/" + id.longValue());
        return id.longValue();
    }

    /** Payload UTF-8 chứa nhãn trùng để kiểm tra chuẩn hóa trước khi lưu PostgreSQL. */
    private String creation() {
        return """
                {"subjectId":1,"content":"Giải thích HashTable.","topics":["Array","array"],
                 "bloomLevel":"UNDERSTAND","referenceAnswer":"Đáp án nháp","answerTimeLimitSeconds":60}
                """;
    }

    /** Tạo header Bearer để đi qua decoder thật, không dùng jwt() của Spring Security Test. */
    private String bearer(String token) { return "Bearer " + token; }

    /** Kiểm tra SQLSTATE độc lập để mỗi lỗi constraint không làm hỏng transaction của test khác. */
    private void assertSqlState(String expected, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> QuestionPostgresConfig.execute(connection, sql)).isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo(expected);
        }
    }

    /** Tạo database mới bên trong container thuộc bộ test; tự hủy cùng container khi kết thúc. */
    private Connection freshDatabase(boolean legacy) throws Exception {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + name);
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl().replace("/question_it", "/" + name),
                postgres.getUsername(), postgres.getPassword());
        try {
            if (legacy) QuestionPostgresConfig.execute(connection, new ClassPathResource("question/legacy-question-schema.sql")
                    .getContentAsString(StandardCharsets.UTF_8));
            return connection;
        } catch (Exception exception) {
            connection.close();
            throw exception;
        }
    }

    /** Dữ liệu cũ tối thiểu trước migration, không lấy từ backup hoặc database thật. */
    private void seedLegacy(Connection connection, String bloom) throws Exception {
        QuestionPostgresConfig.execute(connection, """
                INSERT INTO aives.roles(code,name) VALUES ('LECTURER','Lecturer');
                INSERT INTO aives.users(role_id,username,email,full_name) VALUES (1,'legacy','legacy@test.invalid','Legacy');
                INSERT INTO aives.subjects(code,name) VALUES ('LEGACY','Legacy subject');
                """);
        try (var statement = connection.prepareStatement("INSERT INTO aives.questions(subject_id,created_by,content,bloom_level,is_ai_generated,is_approved) VALUES (1,1,'Legacy preserved',?,true,true)")) {
            statement.setString(1, bloom);
            statement.executeUpdate();
        }
    }
}
