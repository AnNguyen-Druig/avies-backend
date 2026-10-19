package com.avies.backend.exam;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.dto.response.SelectedMainQuestion;
import com.avies.backend.entity.StudentExamQuestion;
import com.avies.backend.exception.*;
import com.avies.backend.repository.StudentExamQuestionRepository;
import com.avies.backend.service.ExamQuestionSelectionService;
import com.avies.backend.service.support.ExamQuestionSelector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.bean.override.mockito.*;
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
import java.util.concurrent.atomic.AtomicReference;
import static com.avies.backend.exam.ExamSessionFixture.as;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Concurrency thật trên PostgreSQL; spy chỉ dùng để đặt latch/fault đúng vị trí, không thay thuật toán. */
@SpringJUnitConfig(ExamQuestionSelectionPostgresConfig.class)
@WebAppConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExamQuestionSelectionConcurrencyPostgresIT {
    /** JWT mock để không dùng cấu hình dịch vụ thật. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Latch trước khóa question cho phép thay dữ liệu thật giữa hai lần đọc. */
    @MockitoSpyBean private ExamQuestionSelector selector;
    /** Fault injection sau SQL insert đầu tiên để kiểm tra rollback thực tế. */
    @MockitoSpyBean private StudentExamQuestionRepository links;
    /** Cùng datasource container của các transaction trong test. */
    @Autowired private DataSource source;
    /** Transaction của workflow gọi, không có transaction chung cho cả test. */
    @Autowired private PlatformTransactionManager manager;
    /** Service thật và repository/locks thật. */
    @Autowired private ExamQuestionSelectionService service;
    /** Context để thử API thu hồi đã có. */
    @Autowired private WebApplicationContext context;
    /** JDBC datasource container. */
    private JdbcTemplate jdbc;
    /** Dataset test không liên quan dữ liệu Supabase. */
    private ExamQuestionSelectionFixture fixture;

    /** Reset fixture và spy bởi Spring trước mỗi test. */
    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(source);
        fixture = new ExamQuestionSelectionFixture(jdbc);
        fixture.reset();
    }

    /** Hai request chồng nhau chỉ tạo một bộ, retry lấy lại đúng ID/order cũ. */
    @Test
    void concurrentSelectionCannotCreateOrOverwriteAnotherSet() throws Exception {
        long parent = fixture.session(2), exam = fixture.exam(parent, 4, 1);
        for (int i = 0; i < 5; i++) fixture.question();
        var selected = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var result = new AtomicReference<List<SelectedMainQuestion>>();
        try (var workers = Executors.newFixedThreadPool(1)) {
            Future<?> first = workers.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                result.set(service.selectForStart(exam));
                selected.countDown();
                await(release);
            }));
            try {
                assertThat(selected.await(5, TimeUnit.SECONDS)).isTrue();
                assertCode(() -> select(exam), ErrorCode.EXAM_QUESTION_SELECTION_CONFLICT);
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(select(exam)).extracting(SelectedMainQuestion::getStudentExamQuestionId)
                .containsExactlyElementsOf(result.get().stream().map(SelectedMainQuestion::getStudentExamQuestionId).toList());
        assertCount(2);
    }

    /** Hai sinh viên cùng phiên không thể tính lịch sử đồng thời rồi lấy hai bộ trùng không cần thiết. */
    @Test
    void differentStudentsSerializeAndRetryUsesCommittedHistory() throws Exception {
        long parent = fixture.session(2);
        long firstExam = fixture.exam(parent, 4, 1), secondExam = fixture.exam(parent, 6, 2);
        for (int i = 0; i < 4; i++) fixture.question();
        var selected = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstSet = new AtomicReference<List<SelectedMainQuestion>>();
        try (var workers = Executors.newFixedThreadPool(1)) {
            Future<?> first = workers.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                firstSet.set(service.selectForStart(firstExam));
                selected.countDown(); await(release);
            }));
            try {
                assertThat(selected.await(5, TimeUnit.SECONDS)).isTrue();
                assertCode(() -> select(secondExam), ErrorCode.EXAM_QUESTION_SELECTION_CONFLICT);
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(select(secondExam).stream().map(SelectedMainQuestion::getQuestionId))
                .doesNotContainAnyElementsOf(firstSet.get().stream().map(SelectedMainQuestion::getQuestionId).toList());
        assertCount(4);
    }

    /** API thu hồi không chạy xuyên selection và không xóa bộ sau khi selection đã commit. */
    @Test
    void removalCannotDestroyConcurrentlySelectedQuestions() throws Exception {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1);
        fixture.question();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var selected = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(1)) {
            Future<?> first = workers.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                service.selectForStart(exam); selected.countDown(); await(release);
            }));
            try {
                assertThat(selected.await(5, TimeUnit.SECONDS)).isTrue();
                mvc.perform(as(delete("/student-exams/" + exam), "owner")).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value(1303));
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        mvc.perform(as(delete("/student-exams/" + exam), "owner")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1404));
        assertCount(1);
    }

    /** Thay đổi sau khi random nhưng trước lock phải bị phát hiện, không bốc bù câu khác hoặc lưu bộ thiếu. */
    @ParameterizedTest
    @ValueSource(strings = {"status", "version", "rubric", "criterion"})
    void eligibilityChangesBetweenReadAndLockAbortSelection(String change) throws Exception {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1), question = fixture.question();
        var chosen = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            chosen.countDown(); await(release);
            return result;
        }).when(selector).choose(anyList(), anyList(), anyInt());
        try (var workers = Executors.newFixedThreadPool(1)) {
            Future<ErrorCode> pending = workers.submit(() -> {
                try { select(exam); return null; }
                catch (AppException exception) { return exception.getErrorCode(); }
            });
            try {
                assertThat(chosen.await(5, TimeUnit.SECONDS)).isTrue();
                switch (change) {
                    case "status" -> jdbc.update("UPDATE aives.questions SET status='DRAFT' WHERE id=?", question);
                    case "version" -> jdbc.update("UPDATE aives.questions SET version=version+1 WHERE id=?", question);
                    case "rubric" -> jdbc.update("UPDATE aives.rubric_criteria SET point_weight=2 WHERE rubric_id=(SELECT id FROM aives.rubrics WHERE question_id=?)", question);
                    case "criterion" -> jdbc.update("UPDATE aives.rubric_criteria SET criterion_text='Changed valid text' WHERE rubric_id=(SELECT id FROM aives.rubrics WHERE question_id=?)", question);
                }
            } finally { release.countDown(); }
            assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(change.equals("rubric")
                    ? ErrorCode.EXAM_QUESTION_RUBRIC_INVALID : ErrorCode.EXAM_QUESTION_ELIGIBILITY_CHANGED);
        }
        assertCount(0);
    }

    /** Khóa bảo vệ nội dung, rubric, tiêu chí và cả việc thêm tiêu chí trong suốt transaction Start. */
    @ParameterizedTest
    @ValueSource(strings = {"question", "rubric", "criterion", "insert_criterion"})
    void selectedQuestionAndRubricGraphRemainStableUntilCommit(String target) throws Exception {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1), question = fixture.question();
        long rubric = jdbc.queryForObject("SELECT id FROM aives.rubrics WHERE question_id=?", Long.class, question);
        var selected = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(1)) {
            Future<?> first = workers.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                service.selectForStart(exam); selected.countDown(); await(release);
            }));
            try {
                assertThat(selected.await(5, TimeUnit.SECONDS)).isTrue();
                String sql = switch (target) {
                    case "question" -> "UPDATE aives.questions SET archived_at=now() WHERE id=" + question;
                    case "rubric" -> "UPDATE aives.rubrics SET description='Changed' WHERE id=" + rubric;
                    case "criterion" -> "UPDATE aives.rubric_criteria SET criterion_text='Changed' WHERE rubric_id=" + rubric;
                    default -> "INSERT INTO aives.rubric_criteria(rubric_id,criterion_text,point_weight,display_order) VALUES (" + rubric + ",'Added',1,3)";
                };
                try (Connection connection = source.getConnection()) {
                    execute(connection, "SET lock_timeout='400ms'");
                    assertThatThrownBy(() -> execute(connection, sql)).isInstanceOfSatisfying(SQLException.class,
                            exception -> assertThat(exception.getSQLState()).isEqualTo("55P03"));
                }
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertCount(1);
    }

    /** Writer đang giữ question lock làm selection trả conflict an toàn, không chứa message SQL. */
    @Test
    void concurrentBankWriterProducesSafeConflictThenRechecksEligibility() throws Exception {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1), question = fixture.question();
        try (Connection writer = source.getConnection()) {
            writer.setAutoCommit(false);
            execute(writer, "UPDATE aives.questions SET status='DRAFT' WHERE id=" + question);
            assertCode(() -> select(exam), ErrorCode.EXAM_QUESTION_SELECTION_CONFLICT);
            writer.commit();
        }
        assertCode(() -> select(exam), ErrorCode.EXAM_QUESTION_POOL_INSUFFICIENT);
        assertCount(0);
    }

    /** Fault sau một INSERT thật chứng minh rollback nguyên batch, không chỉ validate trước khi ghi. */
    @Test
    void failureAfterFirstActualInsertRollsBackEverySelectionRow() {
        long parent = fixture.session(2), exam = fixture.exam(parent, 4, 1);
        fixture.question(); fixture.question();
        doAnswer(invocation -> {
            List<StudentExamQuestion> rows = invocation.getArgument(0);
            var first = rows.getFirst();
            jdbc.update("INSERT INTO aives.student_exam_questions(student_exam_id,question_id,question_order) VALUES (?,?,?)",
                    first.getStudentExam().getId(), first.getQuestion().getId(), first.getQuestionOrder());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isEqualTo(1);
            throw new DataIntegrityViolationException("Injected SQL/private grading fault");
        }).when(links).saveAllAndFlush(anyList());
        assertCode(() -> select(exam), ErrorCode.EXAM_QUESTION_SELECTION_CONFLICT);
        assertCount(0);
    }

    /** Ranh giới ghi của caller tin cậy, không tự mở transaction ở selector production. */
    private List<SelectedMainQuestion> select(long exam) {
        return new TransactionTemplate(manager).execute(tx -> service.selectForStart(exam));
    }

    /** Latch có timeout, luôn release trong finally của test để không treo bộ kiểm thử. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout đồng bộ selection test");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
        }
    }

    /** SQL chỉ chạy trên connection container truyền vào. */
    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    /** Kiểm tra connection mới sau khi service commit hoặc rollback. */
    private void assertCount(int count) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isEqualTo(count);
    }

    /** ErrorCode phản hồi không chứa nội dung driver. */
    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AppException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
