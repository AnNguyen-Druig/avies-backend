package com.avies.backend.exam;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.dto.request.ExamSessionCreationRequest;
import com.avies.backend.dto.request.ExamSessionUpdateRequest;
import com.avies.backend.service.ExamSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.avies.backend.exam.ExamSessionFixture.as;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Kiểm tra thứ tự khóa và FK bằng transaction/connection PostgreSQL độc lập, không giả lập concurrency. */
@SpringJUnitConfig(ExamSessionCrudPostgresConfig.class)
@WebAppConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExamSessionConcurrencyPostgresIT {
    /** Decoder giả lập, phần phân quyền/transaction/repository vẫn chạy thật. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Context web để gọi API thật từ các luồng. */
    @Autowired private WebApplicationContext context;
    /** Datasource trong container do test sở hữu. */
    @Autowired private DataSource source;
    /** Transaction manager thật, không dùng transaction chung cho cả test. */
    @Autowired private PlatformTransactionManager transactions;
    /** Service thật để giữ transaction mở có kiểm soát bằng latch. */
    @Autowired private ExamSessionService service;
    /** Client HTTP dùng filter chain hiện có. */
    private MockMvc mvc;
    /** JDBC kiểm tra snapshot và trạng thái chờ khóa tại server. */
    private JdbcTemplate jdbc;
    /** Dữ liệu thử cô lập cho mỗi ca. */
    private ExamSessionFixture fixture;

    /** Tạo fixture và kết nối chỉ trong Docker. */
    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc = new JdbcTemplate(source);
        fixture = new ExamSessionFixture(jdbc);
        fixture.reset();
        fixture.readyRubric(1);
        fixture.readyQuestions();
    }

    /** Không giữ principal ở thread thực thi JUnit. */
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** Hai PATCH chồng transaction: request sau nhận 409, không ghi đè kết quả đang được xử lý. */
    @Test
    void overlappingPatchesReturnConflictInsteadOfOverwriting() throws Exception {
        long id = fixture.create(mvc);
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(1)) {
            Future<?> first = workers.submit(() -> {
                authenticateOwner();
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                        ExamSessionUpdateRequest patch = new ExamSessionUpdateRequest();
                        patch.setTitle("Thay đổi thứ nhất");
                        service.update(id, patch);
                        written.countDown();
                        await(release);
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            try {
                assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
                mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Không được ghi đè\"}"), "owner"))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1303));
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT title FROM aives.exam_sessions WHERE id=?", String.class, id)).isEqualTo("Thay đổi thứ nhất");
        // Request thử lại đổi Main thành 5 phải có đủ ngân hàng theo điều kiện Edit hiện hành.
        fixture.readyQuestions();
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"maxMainQuestions\":5}"), "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.title").value("Thay đổi thứ nhất"));
    }

    /** Thu hồi đang giữ khóa user làm create/update trả 409; sau COMMIT thì trả 403. */
    @ParameterizedTest
    @ValueSource(strings = {"create", "update"})
    void revocationWinsBeforeWriteAndCurrentAssignmentIsRechecked(String operation) throws Exception {
        long id = fixture.create(mvc);
        try (Connection revocation = source.getConnection()) {
            revocation.setAutoCommit(false);
            execute(revocation, "SELECT id FROM aives.users WHERE id=1 FOR UPDATE");
            execute(revocation, "DELETE FROM aives.lecturer_subject_assignments WHERE lecturer_id=1 AND subject_id=1");
            if (operation.equals("create")) {
                mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(fixture.creation()), "owner"))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1303));
            } else {
                mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Không đổi\"}"), "owner"))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1303));
            }
            revocation.commit();
        }
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(fixture.creation()), "owner"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Không đổi\"}"), "owner"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/exam-sessions/" + id), "owner")).andExpect(status().isOk());
    }

    /** Khi create lấy khóa trước, API thu hồi thật đợi COMMIT; phiên hợp lệ vẫn giữ lại sau thu hồi. */
    @Test
    void creationWinsBeforeRevocationAndHistorySurvives() throws Exception {
        var saved = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var id = new AtomicLong();
        ExamSessionCreationRequest request = new ObjectMapper().findAndRegisterModules()
                .readValue(fixture.creation(), ExamSessionCreationRequest.class);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> creation = workers.submit(() -> {
                authenticateOwner();
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                        id.set(service.create(request).getId());
                        saved.countDown();
                        await(release);
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            Future<Integer> revoke = null;
            try {
                assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();
                revoke = workers.submit(() -> mvc.perform(as(post("/lecturer-subject-assignments/revocations")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lecturerId\":1,\"subjectIds\":[1]}"), "admin"))
                        .andReturn().getResponse().getStatus());
                awaitDatabaseLock("aives.users");
            } finally {
                release.countDown();
            }
            creation.get(10, TimeUnit.SECONDS);
            assertThat(revoke).isNotNull();
            assertThat(revoke.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions WHERE id=?", Integer.class, id.get())).isEqualTo(1);
        mvc.perform(as(get("/exam-sessions/" + id.get()), "owner")).andExpect(status().isOk());
        mvc.perform(as(delete("/exam-sessions/" + id.get()), "owner")).andExpect(status().isForbidden());
    }

    /** StudentExam insert giữ KEY SHARE trước: DELETE phải thất bại, không cascade xóa bài mới commit. */
    @Test
    void existingUncommittedStudentAssignmentBlocksDelete() throws Exception {
        long id = fixture.create(mvc);
        try (Connection assignment = source.getConnection()) {
            assignment.setAutoCommit(false);
            execute(assignment, "INSERT INTO aives.student_exams(session_id,student_id) VALUES (" + id + ",4)");
            mvc.perform(as(delete("/exam-sessions/" + id), "owner")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(1303));
            assignment.commit();
        }
        mvc.perform(as(delete("/exam-sessions/" + id), "owner")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1302));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exams WHERE session_id=?", Integer.class, id)).isEqualTo(1);
    }

    /** DELETE lấy khóa trước: insert sau phải chờ và nhận FK violation, không tạo lịch sử rồi bị cascade mất. */
    @Test
    void deleteWinsBeforeNewStudentAssignmentWithoutSilentlyDeletingAChild() throws Exception {
        long id = fixture.create(mvc);
        var deleted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> removal = workers.submit(() -> {
                authenticateOwner();
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                        service.delete(id);
                        deleted.countDown();
                        await(release);
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            Future<String> assignment = null;
            try {
                assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
                assignment = workers.submit(() -> {
                    try (Connection connection = source.getConnection()) {
                        execute(connection, "INSERT INTO aives.student_exams(session_id,student_id) VALUES (" + id + ",4)");
                        return "unexpected-success";
                    } catch (SQLException exception) {
                        return exception.getSQLState();
                    }
                });
                awaitDatabaseLock("INSERT INTO aives.student_exams");
            } finally {
                release.countDown();
            }
            removal.get(10, TimeUnit.SECONDS);
            assertThat(assignment).isNotNull();
            assertThat(assignment.get(10, TimeUnit.SECONDS)).isEqualTo("23503");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions WHERE id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exams WHERE session_id=?", Integer.class, id)).isZero();
    }

    /** Trạng thái parent đổi đồng thời làm PATCH/DELETE xung đột; sau COMMIT kiểm tra lại trạng thái mới. */
    @Test
    void concurrentStateChangeCannotBeOverwritten() throws Exception {
        long id = fixture.create(mvc);
        try (Connection stateChange = source.getConnection()) {
            stateChange.setAutoCommit(false);
            execute(stateChange, "UPDATE aives.exam_sessions SET status='ONGOING' WHERE id=" + id);
            mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Không đổi\"}"), "owner"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1303));
            mvc.perform(as(delete("/exam-sessions/" + id), "owner")).andExpect(status().isConflict());
            stateChange.commit();
        }
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Không đổi\"}"), "owner"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1301));
    }

    /** Bài bắt đầu theo giao thức parent-lock tương lai không chạy xuyên qua PATCH. */
    @Test
    void startedStudentExamUsingParentLockBlocksPatch() throws Exception {
        long id = fixture.create(mvc);
        long studentExam = fixture.studentExam(id, "ASSIGNED");
        try (Connection start = source.getConnection()) {
            start.setAutoCommit(false);
            execute(start, "SELECT id FROM aives.users WHERE id=1 FOR UPDATE");
            execute(start, "SELECT id FROM aives.subjects WHERE id=1 FOR SHARE");
            execute(start, "SELECT id FROM aives.exam_sessions WHERE id=" + id + " FOR UPDATE");
            execute(start, "UPDATE aives.student_exams SET status='IN_PROGRESS',started_at=now() WHERE id=" + studentExam);
            mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Không đổi\"}"), "owner"))
                    .andExpect(status().isConflict());
            start.commit();
        }
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Không đổi\"}"), "owner"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1302));
    }

    /** Gắn principal vào đúng thread đang gọi service thật. */
    private void authenticateOwner() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("owner", "unused",
                List.of(new SimpleGrantedAuthority("ROLE_LECTURER"))));
    }

    /** Đợi latch có giới hạn; không treo suite vô hạn khi một luồng thất bại. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Hết thời gian đồng bộ test");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** Quan sát wait_event_type=Lock tại PostgreSQL để chứng minh transaction thực sự bị chặn. */
    private void awaitDatabaseLock(String queryFragment) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                    + "AND wait_event_type='Lock' AND query ILIKE ?", Integer.class, "%" + queryFragment + "%");
            if (waiting != null && waiting > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Không quan sát được transaction chờ khóa: " + queryFragment);
    }

    /** Chạy SQL chỉ trên connection test được truyền rõ ràng. */
    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
