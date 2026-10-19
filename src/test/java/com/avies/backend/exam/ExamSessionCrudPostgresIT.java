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
    }

    /** Không để principal của lời gọi service trực tiếp rò sang test khác. */
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** Tạo đúng UPCOMING/owner, trim tiêu đề, không tự gán sinh viên hay lấy câu ngân hàng. */
    @Test
    void createsUpcomingWithoutQuestionsOrStudentsAndRunsCrud() throws Exception {
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
