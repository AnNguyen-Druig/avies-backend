package com.avies.backend.assignment;

import com.avies.backend.controller.LecturerSubjectAssignmentController;
import com.avies.backend.exception.exceptions.AssignmentExceptionHandler;
import com.avies.backend.question.QuestionPostgresConfig;
import com.avies.backend.service.impl.LecturerSubjectAssignmentServiceImpl;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Kiểm tra transaction và khóa đồng thời trên PostgreSQL tạm với JWT thật, không dùng Supabase. */
@SpringJUnitConfig(LecturerSubjectAssignmentPostgresIT.Config.class)
@WebAppConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "jwt.signerKey=assignment-integration-test-key-only-never-use-in-production-0123456789abcdef",
        "jwt.valid-duration=3600", "jwt.refreshable-duration=7200"
})
class LecturerSubjectAssignmentPostgresIT {
    /** Tái sử dụng container và migration thật, chỉ bổ sung các bean phân công mới. */
    @Configuration
    @Import({QuestionPostgresConfig.class, LecturerSubjectAssignmentController.class,
            LecturerSubjectAssignmentServiceImpl.class, AssignmentExceptionHandler.class})
    static class Config { }

    /** Context web dùng controller, service và decoder thật. */
    @Autowired private WebApplicationContext context;
    /** Datasource được cấp duy nhất từ container test. */
    @Autowired private DataSource dataSource;
    /** Mã hóa mật khẩu fixture cho luồng đăng nhập thật. */
    @Autowired private PasswordEncoder passwordEncoder;
    /** Bộ gửi request qua filter bảo mật thật. */
    private MockMvc mvc;
    /** JDBC dùng kiểm tra dữ liệu đã commit, không tham gia transaction của service. */
    private JdbcTemplate jdbc;
    /** JWT admin do API đăng nhập thật cấp. */
    private String adminToken;
    /** Đường dẫn chung của API phân công. */
    private static final String URL = "/lecturer-subject-assignments";

    /** Chỉ làm sạch database tạm trong container rồi tạo fixture xác định. */
    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE aives.question_audit_logs, aives.invalidated_tokens, aives.roles, aives.subjects RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO aives.roles(code,name) VALUES ('ADMIN','Admin'),('LECTURER','Lecturer'),('STUDENT','Student')");
        String hash = passwordEncoder.encode("test-password");
        for (String name : List.of("admin", "lecturer", "colleague", "student")) {
            jdbc.update("INSERT INTO aives.users(role_id,username,email,password_hash,full_name) "
                            + "SELECT id,?,?,?,? FROM aives.roles WHERE code=?", name, name + "@test.invalid", hash, name,
                    name.equals("admin") ? "ADMIN" : name.equals("student") ? "STUDENT" : "LECTURER");
        }
        jdbc.update("INSERT INTO aives.subjects(code,name) VALUES ('AAA','Môn A'),('BBB','Môn B')");
        adminToken = login("admin");
    }

    /** Nhiều request phân công cùng lúc đều thành công và chỉ tạo mỗi cặp đúng một lần. */
    @Test
    void concurrentAssignmentsAreIdempotent() throws Exception {
        runConcurrently(List.of("", "", "", "", "", ""));
        assertThat(countAssignments()).isEqualTo(2);
        change("", 2, "[2,1,1]").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changedSubjectIds").isEmpty());
    }

    /** Nhiều request thu hồi đồng thời không xóa lặp gây lỗi và không ảnh hưởng giảng viên khác. */
    @Test
    void concurrentRevocationsAreIdempotent() throws Exception {
        change("", 2, "[1,2]").andExpect(status().isOk());
        change("", 3, "[1]").andExpect(status().isOk());
        runConcurrently(List.of("/revocations", "/revocations", "/revocations", "/revocations"));
        assertThat(countAssignments()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT lecturer_id FROM aives.lecturer_subject_assignments", Long.class)).isEqualTo(3L);
    }

    /** Thêm và thu hồi cùng giảng viên được tuần tự hóa, không để lại nửa lô. */
    @Test
    void concurrentAssignAndRevokeHaveWholeBatchOutcome() throws Exception {
        runConcurrently(List.of("", "/revocations", "", "/revocations"));
        assertThat(countAssignments()).isIn(0L, 2L);
    }

    /** Môn không tồn tại không gây lưu một phần trên database thật ở cả hai POST. */
    @Test
    void invalidBatchLeavesCommittedStateUnchanged() throws Exception {
        change("", 2, "[1,999999]").andExpect(status().isNotFound());
        assertThat(countAssignments()).isZero();
        change("", 2, "[1,2]").andExpect(status().isOk());
        change("/revocations", 2, "[1,999999]").andExpect(status().isNotFound());
        assertThat(countAssignments()).isEqualTo(2);
    }

    /** Lỗi xảy ra sau insert đầu tiên vẫn rollback cả lô; constraint thử nghiệm chỉ ở container. */
    @Test
    void lateInsertFailureRollsBackWholeTransaction() throws Exception {
        jdbc.execute("ALTER TABLE aives.lecturer_subject_assignments ADD CONSTRAINT test_reject_second CHECK (subject_id <> 2)");
        try {
            change("", 2, "[1,2]").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1201));
            assertThat(countAssignments()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE aives.lecturer_subject_assignments DROP CONSTRAINT test_reject_second");
        }
    }

    /** Lỗi khóa ngoại ở cuối lô thu hồi phải khôi phục cả những liên kết đã xóa trước đó. */
    @Test
    void lateDeleteFailureRollsBackWholeTransaction() throws Exception {
        change("", 2, "[1,2]").andExpect(status().isOk());
        jdbc.execute("CREATE TABLE aives.test_assignment_reference (assignment_id BIGINT REFERENCES aives.lecturer_subject_assignments(id) ON DELETE RESTRICT)");
        try {
            jdbc.update("INSERT INTO aives.test_assignment_reference SELECT id FROM aives.lecturer_subject_assignments WHERE subject_id=2");
            change("/revocations", 2, "[1,2]").andExpect(status().isConflict());
            assertThat(countAssignments()).isEqualTo(2);
        } finally {
            jdbc.execute("DROP TABLE aives.test_assignment_reference");
        }
    }

    /** Kiểm tra đủ bốn API bằng JWT thật và giữ câu hỏi/rubric sau thu hồi quyền. */
    @Test
    void realJwtRoundTripPreservesContentAndRestrictsDropdown() throws Exception {
        String lecturerToken = login("lecturer");
        String colleagueToken = login("colleague");
        change("", 2, "[1]").andExpect(status().isOk());
        change("", 3, "[2]").andExpect(status().isOk());
        mvc.perform(get(URL).header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(2));
        mvc.perform(get(URL + "/my-subjects").header("Authorization", "Bearer " + lecturerToken)
                        .param("lecturerId", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.items[0].id").value(1));
        mvc.perform(get(URL + "/my-subjects").header("Authorization", "Bearer " + colleagueToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items[0].id").value(2));
        mvc.perform(get(URL).header("Authorization", "Bearer " + lecturerToken)).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/my-subjects").header("Authorization", "Bearer " + login("student")))
                .andExpect(status().isForbidden());
        String created = mvc.perform(post("/questions").header("Authorization", "Bearer " + lecturerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":1,\"content\":\"Câu hỏi cần giữ\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number questionId = JsonPath.read(created, "$.result.id");
        jdbc.update("INSERT INTO aives.rubrics(subject_id,title) SELECT subject_id,'Rubric cần giữ' FROM aives.questions WHERE id=?", questionId.longValue());
        jdbc.update("INSERT INTO aives.rubric_criteria(rubric_id,criterion_text,point_weight) SELECT id,'Tiêu chí',10 FROM aives.rubrics");
        change("/revocations", 2, "[1]").andExpect(status().isOk());
        mvc.perform(get("/questions/" + questionId).header("Authorization", "Bearer " + lecturerToken))
                .andExpect(status().isNotFound());
        mvc.perform(get(URL + "/my-subjects").header("Authorization", "Bearer " + lecturerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.questions", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.rubrics", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.rubric_criteria", Long.class)).isEqualTo(1L);
    }

    /** Dữ liệu sau API vẫn được unique constraint, FK và ON DELETE RESTRICT bảo vệ. */
    @Test
    void databaseStillEnforcesReferenceConstraints() throws Exception {
        change("", 2, "[1]").andExpect(status().isOk());
        for (String sql : List.of(
                "INSERT INTO aives.lecturer_subject_assignments(lecturer_id,subject_id) VALUES (2,1)",
                "INSERT INTO aives.lecturer_subject_assignments(lecturer_id,subject_id) VALUES (999999,1)",
                "INSERT INTO aives.lecturer_subject_assignments(lecturer_id,subject_id) VALUES (2,999999)",
                "DELETE FROM aives.users WHERE id=2", "DELETE FROM aives.subjects WHERE id=1")) {
            assertThatThrownBy(() -> jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThat(countAssignments()).isEqualTo(1);
    }

    /** Khởi chạy request cùng thời điểm với transaction/connection riêng và giới hạn thời gian chờ. */
    private void runConcurrently(List<String> suffixes) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(suffixes.size());
        CountDownLatch ready = new CountDownLatch(suffixes.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String suffix : suffixes) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Hết thời gian chờ bắt đầu test");
                    return change(suffix, 2, "[1,2]").andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> result : results) assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /** Gửi POST bằng JWT admin thật, không dùng authentication giả lập. */
    private ResultActions change(String suffix, long lecturerId, String subjectIds) throws Exception {
        return mvc.perform(post(URL + suffix).header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lecturerId\":" + lecturerId + ",\"subjectIds\":" + subjectIds + "}"));
    }

    /** Đăng nhập bằng mật khẩu fixture để nhận token do backend ký. */
    private String login(String username) throws Exception {
        String body = mvc.perform(post("/auth/signin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.result.token");
    }

    /** Đếm dữ liệu đã commit từ một connection độc lập. */
    private long countAssignments() {
        return jdbc.queryForObject("SELECT count(*) FROM aives.lecturer_subject_assignments", Long.class);
    }
}
