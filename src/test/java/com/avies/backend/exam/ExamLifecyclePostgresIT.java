package com.avies.backend.exam;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.service.ExamLifecycleService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.avies.backend.exam.ExamSessionFixture.as;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Kiểm tra vòng đời qua HTTP thật và PostgreSQL riêng; không gọi Supabase, AI hoặc Feature 3 giả. */
@SpringJUnitConfig(ExamLifecyclePostgresConfig.class)
@WebAppConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExamLifecyclePostgresIT {
    /** JWT test; role/ownership/database vẫn chạy thật. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Điều khiển thời gian không cần đợi thật hoặc sửa đồng hồ hệ điều hành. */
    @MockitoBean private Clock clock;
    /** Context web đã bật method security. */
    @Autowired private WebApplicationContext context;
    /** Datasource container mới, không đọc cấu hình ứng dụng. */
    @Autowired private DataSource source;
    /** Transaction cho các ca concurrency thật. */
    @Autowired private PlatformTransactionManager manager;
    /** Service thật được gọi trong transaction giữ mở có kiểm soát. */
    @Autowired private ExamLifecycleService service;
    /** Client HTTP trong bộ nhớ. */
    private MockMvc mvc;
    /** SQL xác minh dữ liệu commit độc lập với persistence context. */
    private JdbcTemplate jdbc;
    /** Dữ liệu môn, người dùng, câu hỏi và rubric hiện hành. */
    private ExamQuestionSelectionFixture fixture;
    /** ID phiên mới cho mỗi test. */
    private long parent;
    /** ID lượt của student fixture. */
    private long exam;

    /** Chạy migration thật và tạo bảng transcripts từ SQL baseline thật trước khi test. */
    @BeforeAll
    void prepareSchema() throws Exception {
        try (Connection connection = source.getConnection()) {
            ExamFoundationPostgresConfig.execute(connection, ExamFoundationPostgresConfig.projectSql(
                    "docs/migrations/20261025_feature2_exam_lifecycle.sql"));
            String baseline = ExamFoundationPostgresConfig.projectSql("docs/database/AIVES_DB_POSTGRESQL_FINAL.sql");
            int start = baseline.indexOf("CREATE TABLE IF NOT EXISTS transcripts (");
            int end = baseline.indexOf("\n);", start) + 3;
            ExamFoundationPostgresConfig.execute(connection, "SET search_path=aives,public;" + baseline.substring(start, end));
        }
    }

    /** Tạo lịch tương lai rồi cố định giờ test đúng đầu slot một; không đổi dữ liệu ngoài container. */
    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(source);
        fixture = new ExamQuestionSelectionFixture(jdbc);
        fixture.reset();
        parent = fixture.session(2);
        exam = fixture.exam(parent, 4, 1);
        fixture.question(); fixture.question();
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        at(fixture.base.start);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** Không giữ authentication của thread sau kiểm thử service trực tiếp. */
    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    /** Start đầu đổi cả hai trạng thái và chỉ trả nội dung snapshot được phép cho sinh viên. */
    @Test
    void startCapturesSnapshotsAndRetryDoesNotRegenerateOrResetTime() throws Exception {
        String first = start();
        assertThat(first).doesNotContain("PRIVATE_REFERENCE_ANSWER", "PRIVATE_RUBRIC", "referenceAnswer", "rubricSnapshot", "finalScore");
        var timestamps = jdbc.queryForMap("SELECT status,started_at,completed_at FROM aives.student_exams WHERE id=?", exam);
        var snapshots = jdbc.queryForList("SELECT * FROM aives.student_exam_questions WHERE student_exam_id=? ORDER BY id", exam);
        String rubric = jdbc.queryForObject("SELECT rubric_snapshot FROM aives.exam_sessions WHERE id=?", String.class, parent);
        jdbc.update("UPDATE aives.questions SET content='Changed',reference_answer='Changed',archived_at=now(),version=version+1");
        jdbc.update("UPDATE aives.rubric_criteria SET criterion_text='Changed'");
        at(fixture.base.start.plusMinutes(5));
        assertThat(start()).isEqualTo(first);
        assertThat(jdbc.queryForMap("SELECT status,started_at,completed_at FROM aives.student_exams WHERE id=?", exam)).isEqualTo(timestamps);
        assertThat(jdbc.queryForList("SELECT * FROM aives.student_exam_questions WHERE student_exam_id=? ORDER BY id", exam)).isEqualTo(snapshots);
        assertThat(jdbc.queryForObject("SELECT rubric_snapshot FROM aives.exam_sessions WHERE id=?", String.class, parent)).isEqualTo(rubric);
    }

    /** Vào muộn không cộng thời gian; deadline luôn là cuối slot, startedAt lấy máy chủ. */
    @Test
    void lateStartKeepsOriginalDeadline() throws Exception {
        at(fixture.base.start.plusMinutes(5));
        mvc.perform(as(post(startUrl()), "student")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.attempt.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.result.attempt.sessionStatus").value("ONGOING"));
        assertThat(jdbc.queryForObject("SELECT started_at FROM aives.student_exams WHERE id=?", OffsetDateTime.class, exam)
                .isEqual(fixture.base.start.plusMinutes(5))).isTrue();
        assertThat(jdbc.queryForObject("SELECT scheduled_end_at FROM aives.student_exams WHERE id=?", OffsetDateTime.class, exam)
                .isEqual(fixture.base.start.plusMinutes(20))).isTrue();
    }

    /** Timestamp Start trả lần đầu và retry phải giống nhau sau khi PostgreSQL lưu ở độ chính xác microsecond. */
    @Test
    void startTimestampPrecisionMatchesDatabaseOnRetry() throws Exception {
        at(fixture.base.start.plusNanos(123456789));
        String first = start();
        at(fixture.base.start.plusMinutes(1));
        assertThat(start()).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT started_at FROM aives.student_exams WHERE id=?", OffsetDateTime.class, exam)
                .getNano()).isEqualTo(123456000);
    }

    /** Đầu slot bao gồm, cuối slot loại trừ; lỗi thời gian không tạo đề hoặc đổi parent. */
    @ParameterizedTest
    @ValueSource(strings = {"before", "end", "after"})
    void rejectsStartOutsideSlotWithoutSideEffects(String variant) throws Exception {
        at(switch (variant) {
            case "before" -> fixture.base.start.minusNanos(1000);
            case "end" -> fixture.base.start.plusMinutes(20);
            default -> fixture.base.start.plusMinutes(21);
        });
        mvc.perform(as(post(startUrl()), "student")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1702));
        assertUnstarted();
    }

    /** Lịch legacy/slot sai không được tự lấp hoặc mở đề thi. */
    @ParameterizedTest
    @ValueSource(strings = {"null", "order", "end", "huge"})
    void rejectsMalformedSlot(String variant) throws Exception {
        if (variant.equals("null")) jdbc.update("UPDATE aives.student_exams SET scheduled_start_at=NULL,scheduled_end_at=NULL WHERE id=?", exam);
        if (variant.equals("order")) jdbc.update("UPDATE aives.student_exams SET slot_order=2 WHERE id=?", exam);
        if (variant.equals("end")) jdbc.update("UPDATE aives.student_exams SET scheduled_end_at=scheduled_end_at-interval '1 second' WHERE id=?", exam);
        if (variant.equals("huge")) jdbc.update("UPDATE aives.student_exams SET slot_order=2147483647 WHERE id=?", exam);
        mvc.perform(as(post(startUrl()), "student")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1701));
        assertUnstarted();
    }

    /** Phân công bị thu hồi chặn Start mới, kể cả sinh viên vẫn thấy lịch sử đã gán. */
    @Test
    void revokedLecturerAssignmentBlocksNewStart() throws Exception {
        jdbc.update("DELETE FROM aives.lecturer_subject_assignments WHERE lecturer_id=1 AND subject_id=1");
        mvc.perform(as(post(startUrl()), "student")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        assertUnstarted();
    }

    /** Không đủ câu/rubric làm rollback cả snapshot rubric đã capture trước khi chọn bộ. */
    @ParameterizedTest
    @ValueSource(strings = {"questions", "rubric"})
    void selectionFailureRollsBackAllStartWrites(String variant) throws Exception {
        if (variant.equals("questions")) jdbc.update("UPDATE aives.questions SET status='DRAFT',is_approved=false");
        else jdbc.update("DELETE FROM aives.rubric_criteria");
        mvc.perform(as(post(startUrl()), "student")).andExpect(status().isConflict());
        assertUnstarted();
        assertThat(jdbc.queryForObject("SELECT rubric_snapshot FROM aives.exam_sessions WHERE id=?", String.class, parent)).isNull();
    }

    /** Mã 1501 là lỗi chuẩn bị rubric, không phải lỗi thời gian; tái hiện từng nguyên nhân trên database tạm. */
    @ParameterizedTest
    @ValueSource(strings = {"missingRubric", "emptyCriteria", "wrongTotal", "zeroWeight", "duplicateOrder", "blankText"})
    void invalidSubjectRubricPreventsStartEvenInsideTheCorrectSlot(String variant) throws Exception {
        String sql = switch (variant) {
            case "missingRubric" -> "DELETE FROM aives.rubrics WHERE subject_id=1";
            case "emptyCriteria" -> "DELETE FROM aives.rubric_criteria";
            case "wrongTotal" -> "UPDATE aives.rubric_criteria SET point_weight=1";
            case "zeroWeight" -> "UPDATE aives.rubric_criteria SET point_weight=0 WHERE display_order=1";
            case "duplicateOrder" -> "UPDATE aives.rubric_criteria SET display_order=1";
            default -> "UPDATE aives.rubric_criteria SET criterion_text='  '";
        };
        jdbc.update(sql);
        mvc.perform(as(post(startUrl()), "student")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1501));
        assertUnstarted();
        assertThat(jdbc.queryForObject("SELECT rubric_snapshot FROM aives.exam_sessions WHERE id=?", String.class, parent)).isNull();
    }

    /** Không biến lỗi ghi sau selection thành một bộ snapshot mồ côi đã commit. */
    @Test
    void failureAfterSelectionRollsBackSnapshotsAndStatuses() throws Exception {
        jdbc.execute("CREATE FUNCTION aives.reject_test_start() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='IN_PROGRESS' THEN RAISE EXCEPTION 'test fault' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_test_start BEFORE UPDATE ON aives.student_exams FOR EACH ROW EXECUTE FUNCTION aives.reject_test_start()");
        try {
            mvc.perform(as(post(startUrl()), "student")).andExpect(status().isConflict());
            assertUnstarted();
            assertThat(jdbc.queryForObject("SELECT rubric_snapshot FROM aives.exam_sessions WHERE id=?", String.class, parent)).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_start ON aives.student_exams");
            jdbc.execute("DROP FUNCTION aives.reject_test_start()");
        }
    }

    /** Các trạng thái đã kết thúc không được mở lại hoặc tạo câu mới. */
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "ABSENT", "EXPIRED"})
    void terminalAttemptCannotStart(String state) throws Exception {
        jdbc.update("UPDATE aives.student_exams SET status=? WHERE id=?", state, exam);
        mvc.perform(as(post(startUrl()), "student")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1700));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isZero();
    }

    /** Cấm truy cập lượt người khác và các role không phải STUDENT ở cả Start/Finish. */
    @ParameterizedTest
    @ValueSource(strings = {"starts", "completions"})
    void attemptsAreScopedAndRoleProtected(String action) throws Exception {
        String url = "/student-exams/" + exam + "/" + action;
        mvc.perform(post(url)).andExpect(status().isUnauthorized());
        for (String actor : List.of("owner", "admin")) mvc.perform(as(post(url), actor)).andExpect(status().isForbidden());
        var otherStudent = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(token -> token.subject("student2")).authorities(new SimpleGrantedAuthority("ROLE_STUDENT"));
        mvc.perform(post(url).with(otherStudent)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1400));
        mvc.perform(as(post("/student-exams/999999/" + action), "student")).andExpect(status().isNotFound());
    }

    /** Token STUDENT cũ không vượt qua role hiện tại trong database. */
    @Test
    void staleStudentRoleCannotStart() throws Exception {
        jdbc.update("UPDATE aives.users SET role_id=2 WHERE id=4");
        mvc.perform(as(post(startUrl()), "student")).andExpect(status().isForbidden());
        assertUnstarted();
    }

    /** Không nhận câu trả lời, điểm hoặc trạng thái giả trong cả ba lệnh vòng đời. */
    @ParameterizedTest
    @ValueSource(strings = {"starts", "completions", "closures"})
    void commandBodyCannotOverrideServerEvidence(String action) throws Exception {
        String url = action.equals("closures") ? closeUrl() : "/student-exams/" + exam + "/" + action;
        String actor = action.equals("closures") ? "owner" : "student";
        for (String body : List.of("{\"finalScore\":10}", "{\"answers\":[\"fake\"]}", "{\"status\":\"COMPLETED\"}",
                "{\"startedAt\":\"2035-01-01T00:00:00Z\"}", "[]", "42"))
            mvc.perform(as(post(url).contentType(MediaType.APPLICATION_JSON).content(body), actor))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1002));
        assertUnstarted();
    }

    /** Không có đáp án, đáp án trắng, chỉ câu AI, thời gian cũ/tương lai hoặc thiếu một câu đều chặn Finish. */
    @ParameterizedTest
    @ValueSource(strings = {"none", "one", "blank", "aiOnly", "beforeStart", "future"})
    void finishRequiresActualSavedAnswerForEveryMainQuestion(String variant) throws Exception {
        start(); at(fixture.base.start.plusMinutes(1));
        List<Long> ids = links();
        if (variant.equals("one")) answer(ids.getFirst(), "Câu trả lời", fixture.base.start.plusSeconds(30), "STUDENT_ANS");
        if (!variant.equals("none") && !variant.equals("one")) for (long id : ids) answer(id,
                variant.equals("blank") ? " \t\n" : "Câu trả lời đã lưu",
                variant.equals("beforeStart") ? fixture.base.start.minusSeconds(1) : variant.equals("future")
                        ? fixture.base.start.plusMinutes(2) : fixture.base.start.plusSeconds(30),
                variant.equals("aiOnly") ? "AI_MAIN_Q" : "STUDENT_ANS");
        mvc.perform(as(post(finishUrl()), "student")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1703));
        assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, exam)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM aives.student_exams WHERE id=?", OffsetDateTime.class, exam)).isNull();
    }

    /** Finish chỉ đổi timestamp/trạng thái, giữ nguyên đáp án đã lưu và không tạo điểm; gọi lại không ghi đè. */
    @Test
    void finishUsesFeature3EvidenceAndIsIdempotentEvenAfterSessionClosure() throws Exception {
        start(); saveAnswers(); at(fixture.base.start.plusMinutes(2));
        jdbc.update("DELETE FROM aives.lecturer_subject_assignments WHERE lecturer_id=1 AND subject_id=1");
        var evidence = jdbc.queryForList("SELECT * FROM aives.transcripts ORDER BY id");
        String first = mvc.perform(as(post(finishUrl()).contentType(MediaType.APPLICATION_JSON).content("{}"), "student"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        at(fixture.base.start.plusHours(3));
        assertThat(mvc.perform(as(post(finishUrl()), "student")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).isEqualTo(first);
        jdbc.update("INSERT INTO aives.lecturer_subject_assignments(lecturer_id,subject_id) VALUES (1,1)");
        mvc.perform(as(post(closeUrl()), "owner")).andExpect(status().isOk());
        mvc.perform(as(post(finishUrl()), "student")).andExpect(status().isOk()).andExpect(jsonPath("$.result.sessionStatus").value("COMPLETED"));
        assertThat(jdbc.queryForList("SELECT * FROM aives.transcripts ORDER BY id")).isEqualTo(evidence);
        assertThat(jdbc.queryForObject("SELECT final_score FROM aives.student_exams WHERE id=?", java.math.BigDecimal.class, exam)).isNull();
        assertThat(jdbc.queryForObject("SELECT ai_suggested_score FROM aives.student_exams WHERE id=?", java.math.BigDecimal.class, exam)).isNull();
    }

    /** Đến đúng cuối slot không thể Finish muộn dù đã có câu trả lời; chờ Close phân loại EXPIRED. */
    @Test
    void lateFinishDoesNotFabricateCompletion() throws Exception {
        start(); saveAnswers(); at(fixture.base.start.plusMinutes(20));
        mvc.perform(as(post(finishUrl()), "student")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1702));
        assertThat(jdbc.queryForObject("SELECT completed_at FROM aives.student_exams WHERE id=?", OffsetDateTime.class, exam)).isNull();
    }

    /** Phiên đóng chỉ sau giờ kết thúc; lỗi không đổi bất kỳ child nào. */
    @Test
    void closeBeforeSessionEndIsRejectedAtomically() throws Exception {
        mvc.perform(as(post(closeUrl()), "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1705));
        assertUnstarted();
    }

    /** Close giữ bài hoàn thành, chuyển người chưa thi/đang thi sang trạng thái riêng, không xóa bằng chứng. */
    @Test
    void closePreservesEvidenceAndClassifiesAbsentExpiredAndCompleted() throws Exception {
        start(); saveAnswers();
        long absent = fixture.exam(parent, 6, 2);
        long completed = fixture.exam(parent, 7, 3);
        jdbc.update("UPDATE aives.student_exams SET status='COMPLETED',started_at=?,completed_at=?,final_score=8.50 WHERE id=?",
                fixture.base.start.plusMinutes(40), fixture.base.start.plusMinutes(50), completed);
        var before = jdbc.queryForList("SELECT * FROM aives.transcripts ORDER BY id");
        var snapshots = jdbc.queryForList("SELECT * FROM aives.student_exam_questions ORDER BY id");
        at(fixture.base.start.plusHours(2));
        String response = mvc.perform(as(post(closeUrl()), "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.completedCount").value(1))
                .andExpect(jsonPath("$.result.absentCount").value(1))
                .andExpect(jsonPath("$.result.expiredCount").value(1)).andReturn().getResponse().getContentAsString();
        assertThat(mvc.perform(as(post(closeUrl()), "owner")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(response);
        assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, absent)).isEqualTo("ABSENT");
        assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, exam)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM aives.student_exams WHERE id=?", OffsetDateTime.class, exam)).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM aives.transcripts ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM aives.student_exam_questions ORDER BY id")).isEqualTo(snapshots);
        mvc.perform(as(get("/student-exams/me"), "student")).andExpect(status().isOk()).andExpect(jsonPath("$.result.items[0].status").value("EXPIRED"));
        mvc.perform(as(post(startUrl()), "student")).andExpect(status().isConflict());
        mvc.perform(as(post(finishUrl()), "student")).andExpect(status().isConflict());
    }

    /** Phiên không ai thi đi thẳng UPCOMING→COMPLETED và ghi vắng, không tạo câu hoặc điểm. */
    @Test
    void allAbsentSessionCanCloseWithoutStartingOrTakingSnapshots() throws Exception {
        at(fixture.base.start.plusHours(2));
        mvc.perform(as(post(closeUrl()), "owner")).andExpect(status().isOk()).andExpect(jsonPath("$.result.absentCount").value(1));
        assertThat(jdbc.queryForObject("SELECT rubric_snapshot FROM aives.exam_sessions WHERE id=?", String.class, parent)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isZero();
    }

    /** Child có dấu hiệu thi nhưng còn ASSIGNED làm rollback tất cả phân loại trước đó. */
    @Test
    void inconsistentChildRollsBackWholeClosure() throws Exception {
        long second = fixture.exam(parent, 6, 2);
        jdbc.update("UPDATE aives.student_exams SET started_at=? WHERE id=?", fixture.base.start, second);
        at(fixture.base.start.plusHours(2));
        mvc.perform(as(post(closeUrl()), "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1704));
        assertUnstarted();
    }

    /** Chỉ owner còn phân công được đóng; ADMIN đọc không được biến thành quyền ghi. */
    @Test
    void closeEnforcesOwnershipAndCurrentAssignment() throws Exception {
        at(fixture.base.start.plusHours(2));
        mvc.perform(post(closeUrl())).andExpect(status().isUnauthorized());
        for (String actor : List.of("admin", "student")) mvc.perform(as(post(closeUrl()), actor)).andExpect(status().isForbidden());
        mvc.perform(as(post(closeUrl()), "other")).andExpect(status().isNotFound());
        jdbc.update("DELETE FROM aives.lecturer_subject_assignments WHERE lecturer_id=1 AND subject_id=1");
        mvc.perform(as(post(closeUrl()), "owner")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        assertUnstarted();
    }

    /** Migration chạy lại giữ dữ liệu hiện có và vẫn từ chối trạng thái ngoài hợp đồng. */
    @Test
    void migrationIsRepeatableAndRejectsUnknownStatus() throws Exception {
        var before = jdbc.queryForList("SELECT * FROM aives.student_exams ORDER BY id");
        try (Connection connection = source.getConnection()) {
            ExamFoundationPostgresConfig.execute(connection, ExamFoundationPostgresConfig.projectSql("docs/migrations/20261025_feature2_exam_lifecycle.sql"));
        }
        assertThat(jdbc.queryForList("SELECT * FROM aives.student_exams ORDER BY id")).isEqualTo(before);
        assertThatThrownBy(() -> jdbc.update("UPDATE aives.student_exams SET status='UNKNOWN' WHERE id=?", exam))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** Hai Start chồng nhau trả 409 thay vì chọn hai bộ; sau commit retry giữ một bộ chính thức. */
    @Test
    void overlappingStartsAreSerializedAndRetryIsIdempotent() throws Exception {
        CountDownLatch written = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            Future<?> first = workers.submit(() -> {
                authenticateStudent();
                try {
                    new TransactionTemplate(manager).executeWithoutResult(transaction -> {
                        service.start(exam); written.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test chờ quá hạn"); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            try {
                assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
                mvc.perform(as(post(startUrl()), "student")).andExpect(status().isConflict());
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        start();
        assertThat(links()).hasSize(2);
    }

    /** Parent/child/bằng chứng đang bị khóa làm command trả 409, không có chuyển trạng thái dở dang. */
    @ParameterizedTest
    @ValueSource(strings = {"parentStart", "studentStart", "childStart", "answerFinish", "parentClose", "childClose"})
    void concurrentLocksReturnConflictWithoutPartialWrites(String variant) throws Exception {
        if (variant.equals("answerFinish")) { start(); saveAnswers(); at(fixture.base.start.plusMinutes(1)); }
        if (variant.endsWith("Close")) at(fixture.base.start.plusHours(2));
        try (Connection blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            String sql = switch (variant) {
                case "childStart" -> "SELECT id FROM aives.student_exams WHERE id=" + exam + " FOR UPDATE";
                case "studentStart" -> "SELECT id FROM aives.users WHERE id=4 FOR UPDATE";
                case "childClose" -> "SELECT id FROM aives.student_exams WHERE id=" + exam + " FOR UPDATE";
                case "answerFinish" -> "SELECT id FROM aives.transcripts FOR UPDATE";
                default -> "SELECT id FROM aives.exam_sessions WHERE id=" + parent + " FOR UPDATE";
            };
            try (var statement = blocker.createStatement()) { statement.execute(sql); }
            String url = variant.equals("answerFinish") ? finishUrl() : variant.endsWith("Close") ? closeUrl() : startUrl();
            mvc.perform(as(post(url), variant.endsWith("Close") ? "owner" : "student")).andExpect(status().isConflict());
            blocker.rollback();
        }
    }

    /** Close cạnh tranh Start/Finish phải đợi retry sau commit, không ghi vắng trên trạng thái chưa chốt. */
    @ParameterizedTest
    @ValueSource(strings = {"start", "finish"})
    void closeCannotRaceWithInFlightStartOrFinish(String action) throws Exception {
        if (action.equals("finish")) { start(); saveAnswers(); at(fixture.base.start.plusMinutes(1)); }
        CountDownLatch written = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            Future<?> first = workers.submit(() -> {
                authenticateStudent();
                try {
                    new TransactionTemplate(manager).executeWithoutResult(transaction -> {
                        if (action.equals("start")) service.start(exam); else service.finish(exam);
                        written.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test chờ quá hạn"); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            try {
                assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
                at(fixture.base.start.plusHours(2));
                mvc.perform(as(post(closeUrl()), "owner")).andExpect(status().isConflict());
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        mvc.perform(as(post(closeUrl()), "owner")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, exam))
                .isEqualTo(action.equals("start") ? "EXPIRED" : "COMPLETED");
    }

    /** Trạng thái và giờ bắt đầu không nhất quán không được dùng để tiếp tục hoặc hoàn thành bài. */
    @ParameterizedTest
    @ValueSource(strings = {"beforeSlot", "future", "missing"})
    void malformedStartedEvidenceBlocksResumeAndFinish(String variant) throws Exception {
        start();
        OffsetDateTime started = variant.equals("missing") ? null : variant.equals("future")
                ? fixture.base.start.plusMinutes(2) : fixture.base.start.minusSeconds(1);
        jdbc.update("UPDATE aives.student_exams SET started_at=? WHERE id=?", started, exam);
        at(fixture.base.start.plusMinutes(1));
        mvc.perform(as(post(startUrl()), "student")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1704));
        mvc.perform(as(post(finishUrl()), "student")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1704));
    }

    /** Câu trả lời của lượt khác không làm lượt hiện tại đủ điều kiện Finish. */
    @Test
    void otherAttemptAnswersDoNotSatisfyFinish() throws Exception {
        start();
        long other = fixture.exam(parent, 6, 2);
        int order = 1;
        for (long question : jdbc.queryForList("SELECT id FROM aives.questions ORDER BY id", Long.class)) {
            long link = fixture.link(other, question, order++);
            answer(link, "Bài của người khác", fixture.base.start.plusSeconds(30), "STUDENT_ANS");
        }
        at(fixture.base.start.plusMinutes(1));
        mvc.perform(as(post(finishUrl()), "student")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1703));
    }

    /** Start/Finish/Close đều kiểm tra ID dương bằng HTTP validation hiện có. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid"})
    void invalidPathIdentifiersAreBadRequests(String id) throws Exception {
        for (String action : List.of("starts", "completions"))
            mvc.perform(as(post("/student-exams/" + id + "/" + action), "student")).andExpect(status().isBadRequest());
        mvc.perform(as(post("/exam-sessions/" + id + "/closures"), "owner")).andExpect(status().isBadRequest());
    }

    /** Đồng hồ chỉ đổi trong test để kiểm tra biên thời gian xác định. */
    private void at(OffsetDateTime time) { when(clock.instant()).thenReturn(time.toInstant()); }
    /** Start qua HTTP thật và kiểm tra bộ hai câu đầy đủ. */
    private String start() throws Exception {
        return mvc.perform(as(post(startUrl()), "student")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.questions.length()").value(2)).andReturn().getResponse().getContentAsString();
    }
    /** Đường dẫn lệnh Start trong hợp đồng API mới. */
    private String startUrl() { return "/student-exams/" + exam + "/starts"; }
    /** Đường dẫn Finish không nhận bài làm trong body. */
    private String finishUrl() { return "/student-exams/" + exam + "/completions"; }
    /** Đường dẫn đóng phiên của giảng viên. */
    private String closeUrl() { return "/exam-sessions/" + parent + "/closures"; }
    /** ID câu đã chốt, không lấy ID ngân hàng để giả làm ID liên kết. */
    private List<Long> links() { return jdbc.queryForList("SELECT id FROM aives.student_exam_questions WHERE student_exam_id=? ORDER BY question_order", Long.class, exam); }
    /** Chuẩn bị bằng chứng đã lưu để test cổng tích hợp Feature 3, không cung cấp endpoint giả. */
    private void saveAnswers() { for (long id : links()) answer(id, "Câu trả lời đã được Feature 3 lưu", fixture.base.start.plusSeconds(30), "STUDENT_ANS"); }
    /** Bằng chứng chỉ ghi vào container test với thời điểm xác định. */
    private void answer(long question, String content, OffsetDateTime time, String type) {
        jdbc.update("INSERT INTO aives.transcripts(student_exam_question_id,turn_no,turn_type,content,created_at) VALUES (?,1,?,?,?)", question, type, content, time);
    }
    /** Sau lỗi Start, mọi status/timestamp và đề vẫn chưa được ghi. */
    private void assertUnstarted() {
        assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, exam)).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT status FROM aives.exam_sessions WHERE id=?", String.class, parent)).isEqualTo("UPCOMING");
        assertThat(jdbc.queryForObject("SELECT started_at FROM aives.student_exams WHERE id=?", OffsetDateTime.class, exam)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isZero();
    }
    /** Danh tính test cho lời gọi service trong worker, không dùng token thật. */
    private void authenticateStudent() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("student", "unused",
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }
}
