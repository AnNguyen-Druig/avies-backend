package com.avies.backend.exam;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.dto.request.*;
import com.avies.backend.service.StudentExamService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import static com.avies.backend.exam.ExamSessionFixture.as;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Nghiệp vụ HTTP/service/JPA thật trên PostgreSQL Docker, không nạp application.yaml hoặc Supabase. */
@SpringJUnitConfig(StudentAssignmentPostgresConfig.class)
@WebAppConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StudentAssignmentPostgresIT {
    /** JWT được test override, không dùng key hoặc dịch vụ xác thực bên ngoài. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Context web và security thật. */
    @Autowired private WebApplicationContext context;
    /** Datasource chỉ thuộc container do suite sở hữu. */
    @Autowired private DataSource source;
    /** Service thật để kiểm tra validation khi gọi trực tiếp. */
    @Autowired private StudentExamService service;
    /** HTTP client dùng filter thật. */
    private MockMvc mvc;
    /** JDBC dùng chuẩn bị và đối chiếu dữ liệu test. */
    private JdbcTemplate jdbc;
    /** Fixture phiên cũ được tái sử dụng, không thay đổi dữ liệu người dùng. */
    private ExamSessionFixture fixture;

    /** Tạo sinh viên test bổ sung và dữ liệu có secret để kiểm tra không rò rỉ DTO. */
    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc = new JdbcTemplate(source);
        fixture = new ExamSessionFixture(jdbc);
        fixture.reset();
        fixture.readyRubric(1);
        fixture.readyQuestions();
        for (String name : List.of("student2", "student3", "student4", "percent%_!")) {
            jdbc.update("INSERT INTO aives.users(role_id,username,email,full_name,password_hash) VALUES (3,?,?,?,'DO_NOT_EXPOSE_HASH')",
                    name, name + "@test.invalid", "Sinh viên " + name);
        }
    }

    /** Không để security context của ca gọi service trực tiếp ảnh hưởng test sau. */
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** Batch gộp ID trùng theo thứ tự đầu tiên và tính timestamp từng slot đúng instant. */
    @Test
    void assignsWholeBatchWithCorrectSlotsAndOffsets() throws Exception {
        long session = fixture.create(mvc);
        mvc.perform(assign(session, "[6,4,6,7]", "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.created.length()").value(3))
                .andExpect(jsonPath("$.result.unchanged").isEmpty())
                .andExpect(jsonPath("$.result.created[0].studentId").value(6))
                .andExpect(jsonPath("$.result.created[1].studentId").value(4))
                .andExpect(jsonPath("$.result.created[2].slotOrder").value(3));
        var starts = jdbc.queryForList("SELECT scheduled_start_at FROM aives.student_exams ORDER BY slot_order", OffsetDateTime.class);
        var ends = jdbc.queryForList("SELECT scheduled_end_at FROM aives.student_exams ORDER BY slot_order", OffsetDateTime.class);
        for (int i = 0; i < 3; i++) {
            assertThat(starts.get(i).toInstant()).isEqualTo(fixture.start.plusMinutes(i * 20L).toInstant());
            assertThat(ends.get(i).toInstant()).isEqualTo(fixture.start.plusMinutes((i + 1L) * 20).toInstant());
            if (i > 0) assertThat(starts.get(i).toInstant()).isEqualTo(ends.get(i - 1).toInstant());
        }
        assertThat(jdbc.queryForList("SELECT status FROM aives.student_exams", String.class)).containsOnly("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exams WHERE started_at IS NOT NULL OR completed_at IS NOT NULL OR final_score IS NOT NULL", Integer.class)).isZero();
    }

    /** Request lặp giữ ID/lịch; batch hỗn hợp chỉ tạo sinh viên chưa có. */
    @Test
    void repeatAndMixedBatchesAreIdempotent() throws Exception {
        long session = fixture.create(mvc);
        mvc.perform(assign(session, "[4,6]", "owner")).andExpect(status().isOk());
        var before = jdbc.queryForList("SELECT id,student_id,slot_order,scheduled_start_at,scheduled_end_at FROM aives.student_exams ORDER BY id");
        mvc.perform(assign(session, "[6,4,6]", "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.created").isEmpty()).andExpect(jsonPath("$.result.unchanged.length()").value(2));
        assertThat(jdbc.queryForList("SELECT id,student_id,slot_order,scheduled_start_at,scheduled_end_at FROM aives.student_exams ORDER BY id")).isEqualTo(before);
        mvc.perform(assign(session, "[4,7]", "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.created[0].slotOrder").value(3))
                .andExpect(jsonPath("$.result.unchanged[0].studentId").value(4));
        assertCount(3);
    }

    /** Một ID thiếu hoặc role sai làm toàn batch rollback, không tạo riêng phần hợp lệ. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "lecturer", "admin"})
    void invalidStudentMakesBatchAtomic(String kind) throws Exception {
        long session = fixture.create(mvc);
        String ids = kind.equals("missing") ? "[4,999999]" : kind.equals("admin") ? "[4,5]" : "[4,2]";
        mvc.perform(assign(session, ids, "owner")).andExpect(status().is(kind.equals("missing") ? 404 : 400))
                .andExpect(jsonPath("$.code").value(kind.equals("missing") ? 1006 : 1401));
        assertCount(0);
    }

    /** Không có enrollment schema nên sinh viên không cần quan hệ môn để được tìm và gán. */
    @Test
    void eligibleSearchIsPagedLiteralAndMinimal() throws Exception {
        mvc.perform(as(get("/students/eligible?page=0&size=2"), "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(5))
                .andExpect(jsonPath("$.result.items.length()").value(2))
                .andExpect(jsonPath("$.result.items[0].id").value(4))
                .andExpect(jsonPath("$.result.items[0].email").doesNotExist())
                .andExpect(jsonPath("$.result.items[0].passwordHash").doesNotExist());
        mvc.perform(as(get("/students/eligible").param("query", "%_!"), "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.items[0].username").value("percent%_!"));
        mvc.perform(as(get("/students/eligible").param("query", "STUDENT2"), "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items[0].id").value(6));
        jdbc.update("UPDATE aives.users SET role_id=2 WHERE id=4");
        mvc.perform(as(get("/students/eligible"), "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(4));
    }

    /** Ownership chặn cả người có cùng môn; người sở hữu không còn phân công bị chặn ghi. */
    @Test
    void enforcesOwnerAndCurrentAssignmentButPreservesHistoricalRead() throws Exception {
        long session = fixture.create(mvc);
        mvc.perform(assign(session, "[4]", "other")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1300));
        mvc.perform(as(get("/exam-sessions/" + session + "/students"), "other")).andExpect(status().isNotFound());
        mvc.perform(assign(session, "[4]", "owner")).andExpect(status().isOk());
        long exam = examId(session, 4);
        jdbc.update("DELETE FROM aives.lecturer_subject_assignments WHERE lecturer_id=1 AND subject_id=1");
        mvc.perform(assign(session, "[6]", "owner")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        mvc.perform(as(delete("/student-exams/" + exam), "owner")).andExpect(status().isForbidden());
        mvc.perform(as(get("/exam-sessions/" + session + "/students"), "owner")).andExpect(status().isOk());
        mvc.perform(as(get("/exam-sessions/" + session + "/students"), "admin")).andExpect(status().isOk());
        assertCount(1);
    }

    /** Phiên không còn UPCOMING không được gán hoặc gỡ bài. */
    @ParameterizedTest
    @ValueSource(strings = {"ONGOING", "COMPLETED"})
    void rejectsMutationsOnNonUpcomingSession(String state) throws Exception {
        long session = fixture.session(1, 1, state);
        long exam = fixture.studentExam(session, "ASSIGNED");
        mvc.perform(assign(session, "[6]", "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1301));
        mvc.perform(as(delete("/student-exams/" + exam), "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1301));
        assertCount(1);
    }

    /** Sức chứa tính bằng toàn slot, không ghi một phần batch hoặc vượt giờ kết thúc. */
    @Test
    void capacityIsAtomicAndFullSessionAllowsPureRepeat() throws Exception {
        long session = fixture.create(mvc);
        jdbc.update("UPDATE aives.exam_sessions SET scheduled_end_at=scheduled_start_at+interval '45 minutes' WHERE id=?", session);
        mvc.perform(assign(session, "[4,6,7]", "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1402));
        assertCount(0);
        mvc.perform(assign(session, "[4,6]", "owner")).andExpect(status().isOk());
        mvc.perform(assign(session, "[6,4]", "owner")).andExpect(status().isOk()).andExpect(jsonPath("$.result.created").isEmpty());
        mvc.perform(assign(session, "[7]", "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1402));
        assertCount(2);
    }

    /** Xóa không dồn sinh viên khác; lần gán sau tái sử dụng slot trống nhỏ nhất. */
    @Test
    void removalPreservesOtherSchedulesAndReusesSmallestHole() throws Exception {
        long session = fixture.create(mvc);
        mvc.perform(assign(session, "[4,6,7]", "owner")).andExpect(status().isOk());
        long middle = examId(session, 6);
        var before = jdbc.queryForMap("SELECT id,slot_order,scheduled_start_at,scheduled_end_at FROM aives.student_exams WHERE student_id=7");
        mvc.perform(as(delete("/student-exams/" + middle), "owner")).andExpect(status().isOk());
        mvc.perform(as(delete("/student-exams/" + middle), "owner")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1400));
        assertThat(jdbc.queryForMap("SELECT id,slot_order,scheduled_start_at,scheduled_end_at FROM aives.student_exams WHERE student_id=7")).isEqualTo(before);
        mvc.perform(assign(session, "[8]", "owner")).andExpect(status().isOk()).andExpect(jsonPath("$.result.created[0].slotOrder").value(2));
        mvc.perform(as(get("/exam-sessions/" + session + "/students?page=0&size=2"), "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items[0].studentId").value(4))
                .andExpect(jsonPath("$.result.items[1].studentId").value(8))
                .andExpect(jsonPath("$.result.totalElements").value(3));
    }

    /** Thời gian thực tế, score dù bằng 0, audio hoặc feedback đều chứng tỏ bài đã được dùng. */
    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "COMPLETED", "started_at", "completed_at", "ai_suggested_score", "final_score", "audio_record_url", "ai_feedback"})
    void refusesRemovalWhenAnyExamEvidenceExists(String evidence) throws Exception {
        long session = fixture.create(mvc);
        mvc.perform(assign(session, "[4]", "owner")).andExpect(status().isOk());
        long exam = examId(session, 4);
        if (evidence.equals("IN_PROGRESS") || evidence.equals("COMPLETED"))
            jdbc.update("UPDATE aives.student_exams SET status=? WHERE id=?", evidence, exam);
        else if (evidence.endsWith("_at"))
            jdbc.update("UPDATE aives.student_exams SET " + evidence + "=now() WHERE id=?", exam);
        else if (evidence.endsWith("_score"))
            jdbc.update("UPDATE aives.student_exams SET " + evidence + "=0 WHERE id=?", exam);
        else
            jdbc.update("UPDATE aives.student_exams SET " + evidence + "='' WHERE id=?", exam);
        mvc.perform(as(delete("/student-exams/" + exam), "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1404));
        assertCount(1);
    }

    /** Ngay cả câu đã chọn nhưng chưa có transcript cũng phải được bảo toàn, không cascade xóa. */
    @Test
    void preservesAssignedQuestionsAndTheirPotentialTranscriptEvidence() throws Exception {
        long session = fixture.create(mvc);
        mvc.perform(assign(session, "[4]", "owner")).andExpect(status().isOk());
        long exam = examId(session, 4);
        jdbc.update("INSERT INTO aives.questions(subject_id,created_by,content) VALUES (1,1,'Private reference answer')");
        jdbc.update("INSERT INTO aives.student_exam_questions(student_exam_id,question_id,question_order) VALUES (?,(SELECT max(id) FROM aives.questions),1)", exam);
        mvc.perform(as(delete("/student-exams/" + exam), "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1404));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isEqualTo(1);
    }

    /** Student chỉ thấy bài của mình, kể cả khi cố truyền studentId khác; không lộ nội dung thi. */
    @Test
    void studentReadsOnlyOwnScheduleAndNoSensitiveFields() throws Exception {
        long session = fixture.create(mvc);
        mvc.perform(assign(session, "[4,6]", "owner")).andExpect(status().isOk());
        long otherSession = fixture.session(2, 1, "UPCOMING");
        jdbc.update("INSERT INTO aives.student_exams(session_id,student_id,status) VALUES (?,6,'COMPLETED')", otherSession);
        String body = mvc.perform(student(get("/student-exams/me?studentId=6&page=0&size=1"), "student"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.items[0].sessionId").value(session))
                .andExpect(jsonPath("$.result.items[0].subjectCode").value("SWD392"))
                .andExpect(jsonPath("$.result.items[0].slotOrder").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("password", "email", "referenceAnswer", "rubric", "question", "finalScore", "DO_NOT_EXPOSE_HASH");
        mvc.perform(student(get("/student-exams/me"), "student2")).andExpect(status().isOk()).andExpect(jsonPath("$.result.totalElements").value(2));
        mvc.perform(student(get("/exam-sessions/" + session + "/students"), "student")).andExpect(status().isForbidden());
        mvc.perform(as(get("/student-exams/me"), "owner")).andExpect(status().isForbidden());
        mvc.perform(as(delete("/student-exams/" + examId(session, 4)), "other")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1400));
    }

    /** Không fabrication lịch legacy; GET vẫn giữ null và assignment bị chặn để sửa dữ liệu có phê duyệt. */
    @Test
    void legacyNullScheduleIsReadableButCannotBeFabricated() throws Exception {
        long session = fixture.create(mvc);
        long legacy = fixture.studentExam(session, "ASSIGNED");
        mvc.perform(as(get("/exam-sessions/" + session + "/students"), "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items[0].id").value(legacy))
                .andExpect(jsonPath("$.result.items[0].scheduledStartAt").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(student(get("/student-exams/me"), "student")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items[0].slotOrder").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(assign(session, "[6]", "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1403));
        assertThat(jdbc.queryForObject("SELECT slot_order FROM aives.student_exams WHERE id=?", Integer.class, legacy)).isNull();
        assertCount(1);
    }

    /** Parent legacy thiếu lịch, duration hoặc đã qua giờ không được nhận batch mới. */
    @ParameterizedTest
    @ValueSource(strings = {"null_schedule", "null_duration", "past"})
    void refusesUnavailableParentSchedule(String kind) throws Exception {
        long session = fixture.create(mvc);
        if (kind.equals("null_schedule")) jdbc.update("UPDATE aives.exam_sessions SET scheduled_start_at=null,scheduled_end_at=null WHERE id=?", session);
        else if (kind.equals("null_duration")) jdbc.update("UPDATE aives.exam_sessions SET slot_duration_minutes=null WHERE id=?", session);
        else jdbc.update("UPDATE aives.exam_sessions SET scheduled_start_at=now()-interval '2 hours',scheduled_end_at=now()-interval '1 hour' WHERE id=?", session);
        mvc.perform(assign(session, "[4]", "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1403));
        assertCount(0);
    }

    /** Có sinh viên thì lịch bị khóa, nhưng giá trị không đổi và metadata an toàn vẫn được PATCH. */
    @Test
    void freezesScheduleWhileAllowingIdenticalInstantsAndMetadata() throws Exception {
        long session = fixture.create(mvc);
        mvc.perform(assign(session, "[4]", "owner")).andExpect(status().isOk());
        var before = jdbc.queryForList("SELECT * FROM aives.student_exams");
        for (String body : List.of("{\"slotDurationMinutes\":21}", "{\"scheduledStartAt\":\"" + fixture.start.plusMinutes(1) + "\"}",
                "{\"scheduledEndAt\":\"" + fixture.start.plusHours(3) + "\"}")) {
            mvc.perform(as(patch("/exam-sessions/" + session).contentType(MediaType.APPLICATION_JSON).content(body), "owner"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1405));
        }
        String sameInstant = fixture.start.withOffsetSameInstant(java.time.ZoneOffset.UTC).toString();
        // Đổi Main lên 5 chỉ là cấu hình hợp lệ khi ngân hàng có đủ câu đã duyệt.
        fixture.readyQuestions();
        mvc.perform(as(patch("/exam-sessions/" + session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Safe metadata\",\"maxMainQuestions\":5,\"scheduledStartAt\":\"" + sameInstant + "\",\"slotDurationMinutes\":20}"), "owner"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT * FROM aives.student_exams")).isEqualTo(before);
        mvc.perform(as(delete("/exam-sessions/" + session), "owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1302));
        mvc.perform(as(delete("/student-exams/" + examId(session, 4)), "owner")).andExpect(status().isOk());
        mvc.perform(as(patch("/exam-sessions/" + session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotDurationMinutes\":25}"), "owner")).andExpect(status().isOk());
    }

    /** Role DB không còn phù hợp thì token cũ không mở quyền quản lý hoặc đọc lịch student. */
    @Test
    void rejectsStaleAuthoritiesAndDeletedIdentities() throws Exception {
        long session = fixture.create(mvc);
        jdbc.update("UPDATE aives.users SET role_id=1 WHERE id=1");
        mvc.perform(assign(session, "[4]", "owner")).andExpect(status().isForbidden());
        mvc.perform(as(get("/students/eligible"), "owner")).andExpect(status().isForbidden());
        jdbc.update("UPDATE aives.users SET role_id=2 WHERE id=4");
        mvc.perform(student(get("/student-exams/me"), "student")).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM aives.users WHERE id=8");
        mvc.perform(student(get("/student-exams/me"), "student4")).andExpect(status().isUnauthorized());
    }

    /** Validation của service chặn caller nội bộ truyền null hoặc batch rỗng, không chỉ dựa controller. */
    @Test
    void serviceBoundaryValidationAppliesWithoutHttp() {
        assertThatThrownBy(() -> service.assign(null, new StudentAssignmentRequest())).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
        assertThatThrownBy(() -> service.mine(null)).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
    }

    /** Request batch với principal lecturer trong fixture. */
    private MockHttpServletRequestBuilder assign(long session, String ids, String actor) {
        return as(post("/exam-sessions/" + session + "/students").contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentIds\":" + ids + "}"), actor);
    }

    /** Principal STUDENT tường minh cho các username mới mà helper CRUD không biết. */
    private MockHttpServletRequestBuilder student(MockHttpServletRequestBuilder request, String name) {
        return request.with(jwt().jwt(token -> token.subject(name)).authorities(new SimpleGrantedAuthority("ROLE_STUDENT")));
    }

    /** Lấy ID child test sau khi request thật đã commit. */
    private long examId(long session, long student) {
        return jdbc.queryForObject("SELECT id FROM aives.student_exams WHERE session_id=? AND student_id=?", Long.class, session, student);
    }

    /** Đối chiếu tính nguyên tử trên database sau request. */
    private void assertCount(int expected) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exams", Integer.class)).isEqualTo(expected);
    }
}
