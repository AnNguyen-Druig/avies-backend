package com.avies.backend.exam;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.dto.request.ExamSessionSearchRequest;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.service.ExamSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import javax.sql.DataSource;
import java.util.List;
import static com.avies.backend.exam.ExamSessionFixture.as;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP/service/repository thật trên PostgreSQL; mỗi request commit độc lập, không dùng Supabase. */
@SpringJUnitConfig(ExamSessionCrudPostgresConfig.class)
@WebAppConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExamSessionCrudPostgresIT {
    /** Decoder giả lập qua test override; không khởi tạo AuthenticationService hoặc đọc key thật. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Context web thật để kiểm tra method security và exception handler. */
    @Autowired private WebApplicationContext context;
    /** Datasource chỉ trỏ tới PostgreSQL container do suite tạo. */
    @Autowired private DataSource source;
    /** Service thật để thử cả việc gọi trực tiếp không đi qua controller. */
    @Autowired private ExamSessionService service;
    /** HTTP client chạy trong bộ nhớ. */
    private MockMvc mvc;
    /** Dữ liệu mẫu và helper trong Docker. */
    private ExamSessionFixture fixture;
    /** JDBC để kiểm tra dữ liệu commit và chuẩn bị trạng thái nghiệp vụ. */
    private JdbcTemplate jdbc;

    /** Reset dữ liệu chỉ trong container và dựng fixture riêng cho từng ca. */
    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc = new JdbcTemplate(source);
        fixture = new ExamSessionFixture(jdbc);
        fixture.reset();
        fixture.readyRubric(1);
        fixture.readyQuestions();
    }

    /** Không để principal của lời gọi service trực tiếp rò sang test khác. */
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** Đủ ngân hàng nhưng chỉ tạo UPCOMING/owner, trim tiêu đề; chưa gán sinh viên hoặc chọn đề. */
    @Test
    void createsUpcomingWithoutSelectingQuestionsOrAssigningStudentsAndRunsCrud() throws Exception {
        long id = fixture.create(mvc);
        assertThat(jdbc.queryForObject("SELECT lecturer_id FROM aives.exam_sessions WHERE id=?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM aives.exam_sessions WHERE id=?", String.class, id)).isEqualTo("UPCOMING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exams", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isZero();
        mvc.perform(as(get("/exam-sessions/" + id), "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.title").value("Phiên thi mới"))
                .andExpect(jsonPath("$.result.subjectCode").value("SWD392"))
                .andExpect(jsonPath("$.result.lecturerId").value(1))
                .andExpect(jsonPath("$.result.finalScore").doesNotExist())
                .andExpect(jsonPath("$.result.studentExams").doesNotExist())
                .andExpect(jsonPath("$.result.lecturer.email").doesNotExist());
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  Tiêu đề mới  \",\"maxFollowUps\":2}"), "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.title").value("Tiêu đề mới"))
                .andExpect(jsonPath("$.result.maxFollowUps").value(2))
                .andExpect(jsonPath("$.result.maxMainQuestions").value(3)).andExpect(jsonPath("$.result.slotDurationMinutes").value(20));
        assertThat(jdbc.queryForObject("SELECT scheduled_start_at FROM aives.exam_sessions WHERE id=?", java.time.OffsetDateTime.class, id).toInstant())
                .isEqualTo(fixture.start.toInstant());
        mvc.perform(as(delete("/exam-sessions/" + id), "owner")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions WHERE id=?", Integer.class, id)).isZero();
        mvc.perform(as(delete("/exam-sessions/" + id), "owner")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1300));
    }

    /** Câu đúng trạng thái nhưng thiếu Bloom/nội dung cũng không được đếm khác với Start. */
    @ParameterizedTest
    @ValueSource(strings = {"empty", "insufficient", "draft", "pending", "rejected", "archived", "otherSubject", "noBloom", "blankContent", "longContent"})
    void creationRequiresEnoughStartEligibleApprovedQuestions(String variant) throws Exception {
        switch (variant) {
            case "empty" -> jdbc.update("DELETE FROM aives.questions");
            case "insufficient" -> jdbc.update("DELETE FROM aives.questions WHERE id=1");
            case "draft" -> jdbc.update("UPDATE aives.questions SET status='DRAFT' WHERE id=1");
            case "pending" -> jdbc.update("UPDATE aives.questions SET status='PENDING_REVIEW' WHERE id=1");
            case "rejected" -> jdbc.update("UPDATE aives.questions SET status='REJECTED' WHERE id=1");
            case "archived" -> jdbc.update("UPDATE aives.questions SET archived_at=now() WHERE id=1");
            case "otherSubject" -> jdbc.update("UPDATE aives.questions SET subject_id=2 WHERE id=1");
            case "noBloom" -> jdbc.update("UPDATE aives.questions SET bloom_level=NULL WHERE id=1");
            case "blankContent" -> jdbc.update("UPDATE aives.questions SET content='  ' WHERE id=1");
            default -> jdbc.update("UPDATE aives.questions SET content=repeat('x',10001) WHERE id=1");
        }
        var before = jdbc.queryForList("SELECT * FROM aives.questions ORDER BY id");
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(fixture.creation()), "owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1306))
                .andExpect(jsonPath("$.message").value("Bạn phải tạo đủ 3 số câu hỏi chính đã được APPROVED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM aives.questions ORDER BY id")).isEqualTo(before);
    }

    /** Số cần thiết lấy từ Main của request, không dùng hằng số 3 hoặc cộng câu đào sâu. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 2147483647})
    void shortageMessageUsesRequestedMainCount(int required) throws Exception {
        jdbc.update("DELETE FROM aives.questions");
        String body = fixture.creation().replace("\"maxMainQuestions\":3", "\"maxMainQuestions\":" + required);
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(body), "owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1306))
                .andExpect(jsonPath("$.message").value("Bạn phải tạo đủ " + required + " số câu hỏi chính đã được APPROVED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions", Integer.class)).isZero();
    }

    /** Đúng ngưỡng vẫn tạo được, không nhân câu với Follow-ups và không chốt đề trước khi Start. */
    @Test
    void exactlyEnoughMainQuestionsAllowCreationRegardlessOfFollowUps() throws Exception {
        String body = fixture.creation().replace("\"maxFollowUps\":0", "\"maxFollowUps\":20");
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(body), "owner"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.result.maxMainQuestions").value(3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions WHERE rubric_snapshot IS NOT NULL OR rubric_snapshot_at IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.questions", Integer.class)).isEqualTo(3);
    }

    /** Nếu đủ câu ở lần đọc nhưng một câu đang bị chỉnh sửa, không lưu phiên trên dữ liệu chưa ổn định. */
    @Test
    void creationConflictsWithConcurrentQuestionModificationAndCanBeRetried() throws Exception {
        try (var blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("SELECT id FROM aives.questions WHERE id=1 FOR UPDATE");
            }
            mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(fixture.creation()), "owner"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1303));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions", Integer.class)).isZero();
            blocker.rollback();
        }
        fixture.create(mvc);
    }

    /** Chặn tạo ngay khi rubric thiếu/sai, không để phiên mới hoặc snapshot mồ côi được lưu. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "wrongTotal", "zeroWeight", "duplicateOrder", "blankText", "otherSubject"})
    void creationRequiresCompleteSubjectRubric(String variant) throws Exception {
        String sql = switch (variant) {
            case "missing", "otherSubject" -> "DELETE FROM aives.rubrics WHERE subject_id=1";
            case "empty" -> "DELETE FROM aives.rubric_criteria";
            case "wrongTotal" -> "UPDATE aives.rubric_criteria SET point_weight=1";
            case "zeroWeight" -> "UPDATE aives.rubric_criteria SET point_weight=0 WHERE display_order=1";
            case "duplicateOrder" -> "UPDATE aives.rubric_criteria SET display_order=1";
            default -> "UPDATE aives.rubric_criteria SET criterion_text='  '";
        };
        jdbc.update(sql);
        if (variant.equals("otherSubject")) fixture.readyRubric(2);
        var before = jdbc.queryForList("SELECT * FROM aives.rubric_criteria ORDER BY id");
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(fixture.creation()), "owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1305))
                .andExpect(jsonPath("$.message").value("Bạn phải tạo đầy đủ Rubrics cho môn"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM aives.rubric_criteria ORDER BY id")).isEqualTo(before);
    }

    /** Có rubric hợp lệ vẫn chưa chốt snapshot; nguồn có thể cập nhật hợp lệ trước lần Start đầu tiên. */
    @Test
    void creationChecksRubricWithoutFreezingItsSnapshot() throws Exception {
        long id = fixture.create(mvc);
        assertThat(jdbc.queryForObject("SELECT rubric_snapshot IS NULL AND rubric_snapshot_at IS NULL FROM aives.exam_sessions WHERE id=?", Boolean.class, id)).isTrue();
        jdbc.update("UPDATE aives.rubric_criteria SET point_weight=5,criterion_text='Tiêu chí đã cập nhật'");
        fixture.create(mvc);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions WHERE rubric_snapshot IS NOT NULL OR rubric_snapshot_at IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isZero();
    }

    /** Rubric/tiêu chí đang thay đổi phải trả xung đột, không lưu phiên trên dữ liệu chưa được xác minh. */
    @ParameterizedTest
    @ValueSource(strings = {"rubric", "criteria"})
    void creationCannotRaceWithRubricUpdates(String target) throws Exception {
        try (var blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute(target.equals("rubric") ? "SELECT id FROM aives.rubrics WHERE subject_id=1 FOR UPDATE"
                        : "SELECT id FROM aives.rubric_criteria FOR UPDATE");
            }
            mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(fixture.creation()), "owner"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1303));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions", Integer.class)).isZero();
            blocker.rollback();
        }
        fixture.create(mvc);
    }

    /** Phân công cần tồn tại ở thời điểm ghi; môn không tồn tại trả 404, không ghi một phần. */
    @Test
    void rejectsUnassignedLecturerAndMissingSubject() throws Exception {
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(fixture.creation()), "outsider"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON)
                .content(fixture.creation().replace("\"subjectId\":1", "\"subjectId\":99999")), "owner"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1016));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions", Integer.class)).isZero();
    }

    /** Quyền sở hữu được kiểm tra ở service, ngay cả giảng viên khác cũng có phân công cùng môn. */
    @Test
    void nonOwnerReceivesSafe404ForReadUpdateAndDelete() throws Exception {
        long id = fixture.create(mvc);
        mvc.perform(as(get("/exam-sessions/" + id), "other")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1300));
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Sai chủ\"}"), "other"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1300));
        mvc.perform(as(delete("/exam-sessions/" + id), "other")).andExpect(status().isNotFound());
        mvc.perform(as(get("/exam-sessions/99999"), "other")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT title FROM aives.exam_sessions WHERE id=?", String.class, id)).isEqualTo("Phiên thi mới");
    }

    /** Admin chỉ đọc, student không dùng API quản lý; từ chối ghi trước khi thay đổi dữ liệu. */
    @Test
    void adminReadOnlyAndStudentDeniedWithRealService() throws Exception {
        long id = fixture.create(mvc);
        mvc.perform(as(get("/exam-sessions/" + id), "admin")).andExpect(status().isOk());
        mvc.perform(as(get("/exam-sessions"), "admin")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(1));
        for (String actor : List.of("admin", "student")) {
            mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(fixture.creation()), actor))
                    .andExpect(status().isForbidden());
            mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Không cho\"}"), actor))
                    .andExpect(status().isForbidden());
            mvc.perform(as(delete("/exam-sessions/" + id), actor)).andExpect(status().isForbidden());
        }
        mvc.perform(as(get("/exam-sessions"), "student")).andExpect(status().isForbidden());
        mvc.perform(as(get("/exam-sessions/" + id), "student")).andExpect(status().isForbidden());
    }

    /** Thu hồi qua API thật giữ lịch sử sở hữu, chặn tạo/sửa/xóa; phân công lại khôi phục quyền ghi. */
    @Test
    void revocationPreservesHistoricalReadsButRemovesWritePermission() throws Exception {
        long id = fixture.create(mvc);
        mvc.perform(as(post("/lecturer-subject-assignments/revocations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lecturerId\":1,\"subjectIds\":[1]}"), "admin")).andExpect(status().isOk());
        mvc.perform(as(get("/exam-sessions/" + id), "owner")).andExpect(status().isOk());
        mvc.perform(as(get("/exam-sessions"), "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(1));
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(fixture.creation()), "owner"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Không đổi\"}"), "owner"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        mvc.perform(as(delete("/exam-sessions/" + id), "owner")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions WHERE id=?", Integer.class, id)).isEqualTo(1);
        mvc.perform(as(post("/lecturer-subject-assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lecturerId\":1,\"subjectIds\":[1]}"), "admin")).andExpect(status().isOk());
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Khôi phục\"}"), "owner"))
                .andExpect(status().isOk());
    }

    /** Hết quyền trong database làm token cũ mất quyền, kể cả authority tự nhận ADMIN. */
    @ParameterizedTest
    @ValueSource(strings = {"STUDENT", "ADMIN"})
    void staleLecturerAuthorityCannotKeepPreviousPermissions(String currentRole) throws Exception {
        long id = fixture.create(mvc);
        jdbc.update("UPDATE aives.users SET role_id=(SELECT id FROM aives.roles WHERE code=?) WHERE id=1", currentRole);
        mvc.perform(as(get("/exam-sessions/" + id), "owner")).andExpect(status().isForbidden());
        mvc.perform(as(get("/exam-sessions"), "owner")).andExpect(status().isForbidden());
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(fixture.creation()), "owner"))
                .andExpect(status().isForbidden());
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Không đổi\"}"), "owner"))
                .andExpect(status().isForbidden());
        mvc.perform(as(delete("/exam-sessions/" + id), "owner")).andExpect(status().isForbidden());
    }

    /** Token authority ADMIN nhưng database là lecturer không được đọc toàn hệ thống. */
    @Test
    void staleAdminCannotEscalateLecturerReads() throws Exception {
        fixture.create(mvc);
        var stale = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(token -> token.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        mvc.perform(get("/exam-sessions").with(stale)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1004));
    }

    /** Lịch không tương lai, bằng nhau, ngược nhau và offset khác nhau đều xét theo instant. */
    @ParameterizedTest
    @ValueSource(strings = {"past", "equal", "reverse", "offsetReverse"})
    void invalidCreateSchedulesAreRejectedAtomically(String variant) throws Exception {
        String start = fixture.start.toString();
        String end = fixture.start.plusHours(2).toString();
        String changed = fixture.creation();
        if (variant.equals("past")) changed = changed.replace(start, fixture.start.minusDays(8).toString());
        if (variant.equals("equal")) changed = changed.replace(end, start);
        if (variant.equals("reverse")) changed = changed.replace(end, fixture.start.minusMinutes(1).toString());
        if (variant.equals("offsetReverse")) changed = changed.replace(end,
                fixture.start.minusMinutes(1).withOffsetSameInstant(java.time.ZoneOffset.UTC).toString());
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(changed), "owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1304));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions", Integer.class)).isZero();
    }

    /** Từ chối ngay khi tạo nếu phiên thiếu thời gian cho một slot, kể cả chỉ thiếu một phần giây. */
    @ParameterizedTest
    @ValueSource(strings = {"reported", "shortBySecond", "shortByMicrosecond", "offset", "maximumSlot"})
    void rejectsCreateWhenOneStudentCannotFit(String variant) throws Exception {
        var end = fixture.start.plusMinutes(20).minusSeconds(1);
        int slotMinutes = 20;
        if (variant.equals("reported")) {
            end = fixture.start.plusMinutes(121);
            slotMinutes = 200;
        }
        if (variant.equals("shortByMicrosecond")) end = fixture.start.plusMinutes(20).minusNanos(1000);
        if (variant.equals("offset")) end = end.withOffsetSameInstant(java.time.ZoneOffset.UTC);
        if (variant.equals("maximumSlot")) slotMinutes = Integer.MAX_VALUE;
        String body = fixture.creation().replace(fixture.start.plusHours(2).toString(), end.toString())
                .replace("\"slotDurationMinutes\":20", "\"slotDurationMinutes\":" + slotMinutes);
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(body), "owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1304));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions", Integer.class)).isZero();
    }

    /** Chấp nhận phiên bằng đúng một slot hoặc dài hơn; so sánh theo instant dù offset khác nhau. */
    @ParameterizedTest
    @ValueSource(strings = {"exact", "longer", "offset"})
    void acceptsCreateWithAtLeastOneCompleteSlot(String variant) throws Exception {
        var end = fixture.start.plusMinutes(20);
        if (variant.equals("longer")) end = end.plusSeconds(1);
        if (variant.equals("offset")) end = end.withOffsetSameInstant(java.time.ZoneOffset.UTC);
        String body = fixture.creation().replace(fixture.start.plusHours(2).toString(), end.toString());
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(body), "owner"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.result.slotDurationMinutes").value(20));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions", Integer.class)).isEqualTo(1);
    }

    /** Sửa riêng start, end hoặc slot đều kiểm tra trạng thái gộp và rollback cả tiêu đề khi thiếu thời gian. */
    @ParameterizedTest
    @ValueSource(strings = {"start", "end", "slot"})
    void rejectsPatchThatWouldMakeOneStudentUnableToFit(String variant) throws Exception {
        long id = fixture.create(mvc);
        var before = jdbc.queryForMap("SELECT * FROM aives.exam_sessions WHERE id=?", id);
        String change = switch (variant) {
            case "start" -> "\"scheduledStartAt\":\"" + fixture.start.plusMinutes(101) + "\"";
            case "end" -> "\"scheduledEndAt\":\"" + fixture.start.plusMinutes(20).minusNanos(1000) + "\"";
            default -> "\"slotDurationMinutes\":121";
        };
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Không được lưu\"," + change + "}"), "owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1304));
        assertThat(jdbc.queryForMap("SELECT * FROM aives.exam_sessions WHERE id=?", id)).isEqualTo(before);
    }

    /** PATCH có thể đổi cả lịch và slot nếu trạng thái cuối hợp lệ và bằng đúng một slot. */
    @Test
    void acceptsMergedPatchThatFitsExactlyOneStudent() throws Exception {
        long id = fixture.create(mvc);
        String body = "{\"slotDurationMinutes\":200,\"scheduledEndAt\":\"%s\"}"
                .formatted(fixture.start.plusMinutes(200));
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content(body), "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.slotDurationMinutes").value(200));
        assertThat(jdbc.queryForObject("SELECT slot_duration_minutes FROM aives.exam_sessions WHERE id=?", Integer.class, id))
                .isEqualTo(200);
    }

    /** Dữ liệu cũ có slot quá dài vẫn đọc được và được sửa về thời lượng hợp lệ, không tự sửa database. */
    @Test
    void existingOversizedSlotCanBeCorrectedExplicitly() throws Exception {
        long id = fixture.create(mvc);
        jdbc.update("UPDATE aives.exam_sessions SET slot_duration_minutes=200 WHERE id=?", id);
        mvc.perform(as(get("/exam-sessions/" + id), "owner")).andExpect(status().isOk());
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotDurationMinutes\":120}"), "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.slotDurationMinutes").value(120));
    }

    /** Tăng Main không được đi vòng kiểm tra New Exam; mọi trường trong PATCH đều rollback khi thiếu câu. */
    @ParameterizedTest
    @ValueSource(ints = {4, 10, 2147483647})
    void editMainCountRejectsInsufficientPoolAndRollsBackEntirePatch(int requested) throws Exception {
        long id = fixture.create(mvc);
        var before = jdbc.queryForMap("SELECT * FROM aives.exam_sessions WHERE id=?", id);
        String body = "{\"title\":\"Không được lưu\",\"maxMainQuestions\":%d,\"maxFollowUps\":5,\"slotDurationMinutes\":30}"
                .formatted(requested);
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content(body), "owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1306))
                .andExpect(jsonPath("$.message").value("Bạn phải tạo đủ " + requested + " số câu hỏi chính đã được APPROVED"));
        assertThat(jdbc.queryForMap("SELECT * FROM aives.exam_sessions WHERE id=?", id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isZero();
    }

    /** Câu chưa duyệt, đã lưu trữ hoặc khác môn không được tính để Edit vượt số câu thực sự có thể thi. */
    @ParameterizedTest
    @ValueSource(strings = {"draft", "archived", "otherSubject"})
    void editMainCountUsesTheSameEligibilityAsStart(String variant) throws Exception {
        long id = fixture.create(mvc);
        long extra = jdbc.queryForObject("INSERT INTO aives.questions(subject_id,created_by,content,bloom_level,status) "
                + "VALUES (1,1,'Câu thứ tư','REMEMBER','APPROVED') RETURNING id", Long.class);
        switch (variant) {
            case "draft" -> jdbc.update("UPDATE aives.questions SET status='DRAFT' WHERE id=?", extra);
            case "archived" -> jdbc.update("UPDATE aives.questions SET archived_at=now() WHERE id=?", extra);
            default -> jdbc.update("UPDATE aives.questions SET subject_id=2 WHERE id=?", extra);
        }
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMainQuestions\":4}"), "owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1306));
        assertThat(jdbc.queryForObject("SELECT max_main_questions FROM aives.exam_sessions WHERE id=?", Integer.class, id)).isEqualTo(3);
    }

    /** Có đúng ngưỡng mới thì cập nhật được dù đã phân công sinh viên; chưa chọn câu hoặc đóng băng rubric. */
    @Test
    void editMainCountSucceedsWithEnoughQuestionsWithoutSelectingOrFreezing() throws Exception {
        long id = fixture.create(mvc);
        long attempt = fixture.studentExam(id, "ASSIGNED");
        jdbc.update("INSERT INTO aives.questions(subject_id,created_by,content,bloom_level,status) "
                + "VALUES (1,1,'Câu thứ tư','REMEMBER','APPROVED')");
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Đã cập nhật\",\"maxMainQuestions\":4,\"maxFollowUps\":20}"), "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.maxMainQuestions").value(4))
                .andExpect(jsonPath("$.result.title").value("Đã cập nhật"));
        assertThat(jdbc.queryForObject("SELECT rubric_snapshot IS NULL AND rubric_snapshot_at IS NULL FROM aives.exam_sessions WHERE id=?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, attempt)).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isZero();
    }

    /** Giảm Main cũng kiểm tra theo số mới, cho phép sửa khi ngân hàng còn đúng số câu cần dùng. */
    @Test
    void reducingMainCountRequiresOnlyTheNewMinimum() throws Exception {
        long id = fixture.create(mvc);
        jdbc.update("UPDATE aives.questions SET archived_at=now() WHERE id=1");
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMainQuestions\":2}"), "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.maxMainQuestions").value(2));
    }

    /** Giảm Main chưa đủ thấp vẫn bị từ chối; không dùng số cũ hoặc bỏ qua kiểm tra khi giảm. */
    @Test
    void reducingMainCountStillRejectsAnInsufficientNewPool() throws Exception {
        long id = fixture.create(mvc);
        jdbc.update("UPDATE aives.questions SET archived_at=now() WHERE id IN (1,2)");
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMainQuestions\":2}"), "owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message")
                        .value("Bạn phải tạo đủ 2 số câu hỏi chính đã được APPROVED"));
        assertThat(jdbc.queryForObject("SELECT max_main_questions FROM aives.exam_sessions WHERE id=?", Integer.class, id)).isEqualTo(3);
    }

    /** Không đổi Main thì giữ hành vi cũ, kể cả nguồn rubric/ngân hàng đã thay đổi sau khi tạo phiên. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"title\":\"Tên mới\"}", "{\"maxFollowUps\":2}", "{\"maxMainQuestions\":3,\"title\":\"Tên mới\"}"})
    void patchesWithoutAnActualMainCountChangeDoNotRequireTheCurrentPool(String body) throws Exception {
        long id = fixture.create(mvc);
        jdbc.update("DELETE FROM aives.rubrics");
        jdbc.update("DELETE FROM aives.questions");
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content(body), "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.maxMainQuestions").value(3));
    }

    /** Main thay đổi yêu cầu rubric hợp lệ như New Exam, lỗi không làm lưu một phần cấu hình. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrongTotal"})
    void editMainCountRequiresAReadyRubric(String variant) throws Exception {
        long id = fixture.create(mvc);
        if (variant.equals("missing")) jdbc.update("DELETE FROM aives.rubrics");
        else jdbc.update("UPDATE aives.rubric_criteria SET point_weight=1");
        var before = jdbc.queryForMap("SELECT * FROM aives.exam_sessions WHERE id=?", id);
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMainQuestions\":2,\"title\":\"Không được lưu\"}"), "owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1305));
        assertThat(jdbc.queryForMap("SELECT * FROM aives.exam_sessions WHERE id=?", id)).isEqualTo(before);
    }

    /** Edit không chốt cấu hình dựa trên câu đang bị sửa; xung đột giữ nguyên phiên và cho phép thử lại. */
    @Test
    void editMainCountConflictsWithQuestionWriterAndRollsBack() throws Exception {
        long id = fixture.create(mvc);
        var before = jdbc.queryForMap("SELECT * FROM aives.exam_sessions WHERE id=?", id);
        try (var blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("SELECT id FROM aives.questions WHERE id=1 FOR UPDATE");
            }
            mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"maxMainQuestions\":2,\"title\":\"Không được lưu\"}"), "owner"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1303));
            assertThat(jdbc.queryForMap("SELECT * FROM aives.exam_sessions WHERE id=?", id)).isEqualTo(before);
            blocker.rollback();
        }
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMainQuestions\":2}"), "owner"))
                .andExpect(status().isOk());
    }

    /** PATCH chỉ gửi end vẫn phải được kiểm tra cùng start cũ; title đi kèm cũng rollback khi lỗi. */
    @Test
    void patchValidatesMergedStateAndRollsBackAllFields() throws Exception {
        long id = fixture.create(mvc);
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Không được lưu\",\"scheduledEndAt\":\"" + fixture.start.minusMinutes(1) + "\"}"), "owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1304));
        assertThat(jdbc.queryForObject("SELECT title FROM aives.exam_sessions WHERE id=?", String.class, id)).isEqualTo("Phiên thi mới");
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduledStartAt\":\"" + fixture.start.plusHours(3) + "\"}"), "owner"))
                .andExpect(status().isBadRequest());
    }

    /** Giữ dữ liệu cũ không lịch vẫn đọc được; sửa yêu cầu bổ sung lịch hợp lệ đầy đủ. */
    @Test
    void legacySessionRemainsReadableAndCanReceiveScheduleExplicitly() throws Exception {
        long id = jdbc.queryForObject("INSERT INTO aives.exam_sessions(lecturer_id,subject_id,title) VALUES (1,1,'Legacy') RETURNING id", Long.class);
        mvc.perform(as(get("/exam-sessions/" + id), "owner")).andExpect(status().isOk());
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"New\"}"), "owner"))
                .andExpect(status().isBadRequest());
        String patchBody = "{\"scheduledStartAt\":\"%s\",\"scheduledEndAt\":\"%s\",\"slotDurationMinutes\":15}".formatted(fixture.start, fixture.start.plusHours(1));
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content(patchBody), "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.title").value("Legacy"));
    }

    /** Phiên ONGOING/COMPLETED không được sửa hoặc xóa, kể cả chủ sở hữu còn phân công. */
    @ParameterizedTest
    @ValueSource(strings = {"ONGOING", "COMPLETED"})
    void rejectsNonUpcomingMutations(String sessionStatus) throws Exception {
        long id = fixture.session(1L, 1L, sessionStatus);
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Không đổi\"}"), "owner"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1301));
        mvc.perform(as(delete("/exam-sessions/" + id), "owner")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1301));
    }

    /** Bất kỳ StudentExam nào đều chặn xóa; bài đang/đã thi cũng chặn PATCH. */
    @ParameterizedTest
    @ValueSource(strings = {"ASSIGNED", "IN_PROGRESS", "COMPLETED"})
    void protectsStudentExamHistory(String examStatus) throws Exception {
        long id = fixture.session(1L, 1L, "UPCOMING");
        long studentExam = fixture.studentExam(id, examStatus);
        jdbc.update("INSERT INTO aives.questions(subject_id,created_by,content) VALUES (1,1,'Question protected')");
        jdbc.update("INSERT INTO aives.student_exam_questions(student_exam_id,question_id,question_order) VALUES (?,(SELECT max(id) FROM aives.questions),1)", studentExam);
        mvc.perform(as(delete("/exam-sessions/" + id), "owner")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1302));
        int expected = examStatus.equals("ASSIGNED") ? 200 : 409;
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Đổi hợp lệ khi chưa thi\"}"), "owner"))
                .andExpect(status().is(expected));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exams WHERE id=?", Integer.class, studentExam)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isEqualTo(1);
    }

    /** Timestamp thực tế đã có phải chặn sửa dù status StudentExam vẫn ASSIGNED. */
    @ParameterizedTest
    @ValueSource(strings = {"started_at", "completed_at"})
    void timestampEvidenceOfStartedExamBlocksPatch(String column) throws Exception {
        long id = fixture.session(1L, 1L, "UPCOMING");
        long studentExam = fixture.studentExam(id, "ASSIGNED");
        jdbc.update("UPDATE aives.student_exams SET " + column + "=now() WHERE id=?", studentExam);
        mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Không đổi\"}"), "owner"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1302));
    }

    /** Scope và count được lọc trước phân trang; lecturerId client gửi không thay đổi danh tính. */
    @Test
    void searchFiltersScopesCountsAndStableOrdering() throws Exception {
        long first = fixture.session(1, 1, "UPCOMING");
        long second = fixture.session(1, 2, "COMPLETED");
        long third = fixture.session(1, 1, "UPCOMING");
        fixture.session(2, 1, "UPCOMING");
        mvc.perform(as(get("/exam-sessions?page=0&size=1&lecturerId=2"), "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(3)).andExpect(jsonPath("$.result.items[0].id").value(third));
        mvc.perform(as(get("/exam-sessions?page=1&size=1"), "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items[0].id").value(second));
        mvc.perform(as(get("/exam-sessions?subjectId=1&status=UPCOMING"), "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.items[0].id").value(third)).andExpect(jsonPath("$.result.items[1].id").value(first));
        mvc.perform(as(get("/exam-sessions"), "admin")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(4));
        mvc.perform(as(get("/exam-sessions?subjectId=99999"), "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(0));
        mvc.perform(as(get("/exam-sessions"), "outsider")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items").isEmpty());
    }

    /** Tầng service tự bảo vệ quyền và validation khi có lời gọi không qua HTTP. */
    @Test
    void directServiceCallsEnforceAuthenticationRoleAndValidation() {
        assertThatThrownBy(() -> service.search(new ExamSessionSearchRequest())).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThatThrownBy(() -> service.get(1L)).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "unused",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        ExamSessionSearchRequest invalid = new ExamSessionSearchRequest();
        invalid.setSize(101);
        assertThatThrownBy(() -> service.search(invalid)).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
    }

    /** Token của user đã mất phải trả 401, không NPE hoặc giữ quyền theo authority. */
    @Test
    void deletedUserTokenGets401() throws Exception {
        jdbc.update("DELETE FROM aives.users WHERE username='outsider'");
        mvc.perform(as(get("/exam-sessions"), "outsider")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1003));
        mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(fixture.creation()), "outsider"))
                .andExpect(status().isUnauthorized());
    }

    /** PATCH không thay đổi giá trị được gửi lại an toàn; không tự nhân bản phiên thi. */
    @Test
    void repeatedNoOpPatchDoesNotCreateAdditionalRecords() throws Exception {
        long id = fixture.create(mvc);
        for (int i = 0; i < 2; i++) {
            mvc.perform(as(patch("/exam-sessions/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"maxFollowUps\":0}"), "owner"))
                    .andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.exam_sessions", Integer.class)).isEqualTo(1);
    }
}
