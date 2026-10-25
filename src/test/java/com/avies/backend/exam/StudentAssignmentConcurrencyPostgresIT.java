package com.avies.backend.exam;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.dto.request.StudentAssignmentRequest;
import com.avies.backend.service.StudentExamService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import java.util.concurrent.*;
import static com.avies.backend.exam.ExamSessionFixture.as;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Giao thức khóa được kiểm chứng bằng nhiều connection/transaction thật trong container PostgreSQL riêng. */
@SpringJUnitConfig(StudentAssignmentPostgresConfig.class)
@WebAppConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StudentAssignmentConcurrencyPostgresIT {
    /** Không dùng key thật hoặc AuthenticationService trong test. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Web và filter chain thật. */
    @Autowired private WebApplicationContext context;
    /** Chỉ datasource Docker do config test khởi tạo. */
    @Autowired private DataSource source;
    /** Giữ transaction service mở bằng latch để tạo cuộc đua có kiểm soát. */
    @Autowired private PlatformTransactionManager transactions;
    /** Service thật, không mock thuật toán hoặc repository. */
    @Autowired private StudentExamService service;
    /** HTTP client trong bộ nhớ. */
    private MockMvc mvc;
    /** JDBC cho dữ liệu giả và quan sát lock wait phía PostgreSQL. */
    private JdbcTemplate jdbc;
    /** Helper parent session đã được kiểm thử ở CRUD. */
    private ExamSessionFixture fixture;

    /** Chuẩn bị fixture độc lập, sinh viên bổ sung có ID 6 và 7. */
    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc = new JdbcTemplate(source);
        fixture = new ExamSessionFixture(jdbc);
        fixture.reset();
        fixture.readyRubric(1);
        fixture.readyQuestions();
        jdbc.update("INSERT INTO aives.users(role_id,username,email,full_name) VALUES (3,'student2','s2@test.invalid','S2'),(3,'student3','s3@test.invalid','S3')");
    }

    /** Xóa security context khỏi thread JUnit sau mỗi ca. */
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** Assignment giữ khóa sẽ chặn batch/PATCH/DELETE chồng nhau; retry sau commit đánh giá trạng thái mới. */
    @ParameterizedTest
    @ValueSource(strings = {"assign", "patch", "delete"})
    void overlappingAssignmentDoesNotRaceWithOtherParentMutations(String operation) throws Exception {
        long session = fixture.create(mvc);
        var saved = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(1)) {
            Future<?> first = workers.submit(() -> {
                authenticateOwner();
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                        service.assign(session, request(4L));
                        saved.countDown();
                        await(release);
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            try {
                assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();
                var competitor = operation.equals("assign")
                        ? post("/exam-sessions/" + session + "/students").contentType(MediaType.APPLICATION_JSON).content("{\"studentIds\":[4,6]}")
                        : operation.equals("patch")
                        ? patch("/exam-sessions/" + session).contentType(MediaType.APPLICATION_JSON).content("{\"slotDurationMinutes\":30}")
                        : delete("/exam-sessions/" + session);
                mvc.perform(as(competitor, "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1303));
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        if (operation.equals("assign")) {
            mvc.perform(as(post("/exam-sessions/" + session + "/students").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"studentIds\":[4,6]}"), "owner")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.created[0].slotOrder").value(2))
                    .andExpect(jsonPath("$.result.unchanged[0].studentId").value(4));
        } else if (operation.equals("patch")) {
            mvc.perform(as(patch("/exam-sessions/" + session).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"slotDurationMinutes\":30}"), "owner")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(1405));
        } else {
            mvc.perform(as(delete("/exam-sessions/" + session), "owner")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(1302));
        }
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT slot_order) FROM aives.student_exams", Integer.class))
                .isEqualTo(operation.equals("assign") ? 2 : 1);
    }

    /** Thu hồi chưa commit giữ parent; batch sau retry dùng đúng lỗ trống, không dồn lịch. */
    @Test
    void concurrentRemovalAndAssignmentSafelyReuseFreedSlot() throws Exception {
        long session = fixture.create(mvc);
        assignViaHttp(session, "[4,6]");
        long id = jdbc.queryForObject("SELECT id FROM aives.student_exams WHERE student_id=4", Long.class);
        var deleted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(1)) {
            Future<?> removal = workers.submit(() -> {
                authenticateOwner();
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                        service.remove(id);
                        deleted.countDown();
                        await(release);
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            try {
                assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
                mvc.perform(as(post("/exam-sessions/" + session + "/students").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentIds\":[7]}"), "owner")).andExpect(status().isConflict());
            } finally { release.countDown(); }
            removal.get(10, TimeUnit.SECONDS);
        }
        assignViaHttp(session, "[7]");
        assertThat(jdbc.queryForObject("SELECT slot_order FROM aives.student_exams WHERE student_id=7", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT slot_order FROM aives.student_exams WHERE student_id=6", Integer.class)).isEqualTo(2);
    }

    /** Role user đích đang bị đổi phải gây 409; sau commit role sai bị 400, không có partial insert. */
    @Test
    void concurrentStudentRoleChangeCannotSlipThroughValidation() throws Exception {
        long session = fixture.create(mvc);
        try (Connection change = source.getConnection()) {
            change.setAutoCommit(false);
            execute(change, "UPDATE aives.users SET role_id=2 WHERE id=4");
            mvc.perform(as(post("/exam-sessions/" + session + "/students").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"studentIds\":[6,4]}"), "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1303));
            change.commit();
        }
        mvc.perform(as(post("/exam-sessions/" + session + "/students").contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentIds\":[6,4]}"), "owner")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1401));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exams", Integer.class)).isZero();
    }

    /** Start theo giao thức parent-lock tương lai thắng thì thu hồi không thể xóa bài bắt đầu. */
    @Test
    void startedEvidenceWinsBeforeRemoval() throws Exception {
        long session = fixture.create(mvc);
        assignViaHttp(session, "[4]");
        long id = jdbc.queryForObject("SELECT id FROM aives.student_exams", Long.class);
        try (Connection start = source.getConnection()) {
            start.setAutoCommit(false);
            execute(start, "SELECT id FROM aives.users WHERE id=1 FOR UPDATE");
            execute(start, "SELECT id FROM aives.subjects WHERE id=1 FOR SHARE");
            execute(start, "SELECT id FROM aives.exam_sessions WHERE id=" + session + " FOR UPDATE");
            execute(start, "UPDATE aives.student_exams SET status='IN_PROGRESS',started_at=now() WHERE id=" + id);
            mvc.perform(as(delete("/student-exams/" + id), "owner")).andExpect(status().isConflict());
            start.commit();
        }
        mvc.perform(as(delete("/student-exams/" + id), "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1404));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exams", Integer.class)).isEqualTo(1);
    }

    /** FK KEY SHARE khi gắn câu thắng trước thì khóa child trả 409, không cascade xóa câu mới. */
    @Test
    void questionInsertionWinsBeforeRemoval() throws Exception {
        long session = fixture.create(mvc);
        assignViaHttp(session, "[4]");
        long id = jdbc.queryForObject("SELECT id FROM aives.student_exams", Long.class);
        long question = question();
        try (Connection insert = source.getConnection()) {
            insert.setAutoCommit(false);
            execute(insert, "INSERT INTO aives.student_exam_questions(student_exam_id,question_id,question_order) VALUES (" + id + "," + question + ",1)");
            mvc.perform(as(delete("/student-exams/" + id), "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1303));
            insert.commit();
        }
        mvc.perform(as(delete("/student-exams/" + id), "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1404));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isEqualTo(1);
    }

    /** Nếu thu hồi thắng, insert câu phải đợi rồi thất bại FK, không tạo bằng chứng mồ côi. */
    @Test
    void removalWinsBeforeQuestionInsertionAndFkRejectsOrphan() throws Exception {
        long session = fixture.create(mvc);
        assignViaHttp(session, "[4]");
        long id = jdbc.queryForObject("SELECT id FROM aives.student_exams", Long.class);
        long question = question();
        var removed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> deletion = workers.submit(() -> {
                authenticateOwner();
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                        service.remove(id);
                        removed.countDown();
                        await(release);
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            Future<String> insertion = null;
            try {
                assertThat(removed.await(5, TimeUnit.SECONDS)).isTrue();
                insertion = workers.submit(() -> {
                    try (Connection connection = source.getConnection()) {
                        execute(connection, "SET statement_timeout='6s'");
                        execute(connection, "INSERT INTO aives.student_exam_questions(student_exam_id,question_id,question_order) VALUES (" + id + "," + question + ",1)");
                        return "unexpected-success";
                    } catch (SQLException exception) { return exception.getSQLState(); }
                });
                awaitDatabaseLock();
            } finally { release.countDown(); }
            deletion.get(10, TimeUnit.SECONDS);
            assertThat(insertion).isNotNull();
            assertThat(insertion.get(10, TimeUnit.SECONDS)).isEqualTo("23503");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isZero();
    }

    /** Gọi batch thật ngoài transaction điều khiển latch. */
    private void assignViaHttp(long session, String ids) throws Exception {
        mvc.perform(as(post("/exam-sessions/" + session + "/students").contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentIds\":" + ids + "}"), "owner")).andExpect(status().isOk());
    }

    /** Sinh câu test trong container, không gọi Question API hoặc AI. */
    private long question() {
        return jdbc.queryForObject("INSERT INTO aives.questions(subject_id,created_by,content) VALUES (1,1,'Test evidence') RETURNING id", Long.class);
    }

    /** DTO request hợp lệ cho lời gọi service trực tiếp trong worker. */
    private StudentAssignmentRequest request(Long... ids) {
        var request = new StudentAssignmentRequest();
        request.setStudentIds(List.of(ids));
        return request;
    }

    /** Security context gắn đúng thread đang thực hiện transaction. */
    private void authenticateOwner() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("owner", "unused",
                List.of(new SimpleGrantedAuthority("ROLE_LECTURER"))));
    }

    /** Chờ có giới hạn, luôn cho worker kết thúc nếu test thất bại. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout đồng bộ test");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** Xác nhận FK insert thật sự chờ lock tại PostgreSQL, không chỉ dựa trên sleep. */
    private void awaitDatabaseLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%INSERT INTO aives.student_exam_questions%'", Integer.class);
            if (count != null && count > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Không quan sát được FK insert chờ khóa child");
    }

    /** SQL chỉ được thực hiện trên connection PostgreSQL container truyền rõ ràng. */
    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
