package com.avies.backend.assignment;

import com.avies.backend.config.*;
import com.avies.backend.controller.LecturerSubjectAssignmentController;
import com.avies.backend.controller.QuestionController;
import com.avies.backend.entity.*;
import com.avies.backend.exception.exceptions.AssignmentExceptionHandler;
import com.avies.backend.exception.exceptions.QuestionExceptionHandler;
import com.avies.backend.question.QuestionPersistenceTestConfig;
import com.avies.backend.repository.LecturerSubjectAssignmentRepository;
import com.avies.backend.service.impl.LecturerSubjectAssignmentServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Kiểm tra HTTP → bảo mật → service → H2 thật, không đọc cấu hình Supabase/AI. */
@SpringJUnitConfig(LecturerSubjectAssignmentIntegrationTest.Config.class)
@WebAppConfiguration
@Transactional
class LecturerSubjectAssignmentIntegrationTest {
    /** Ghép cấu hình persistence cô lập với controller và bảo mật thật. */
    @Configuration
    @EnableWebMvc
    @Import({QuestionPersistenceTestConfig.class, LecturerSubjectAssignmentController.class,
            LecturerSubjectAssignmentServiceImpl.class, AssignmentExceptionHandler.class,
            QuestionController.class, QuestionExceptionHandler.class, SecurityConfig.class, CorsProperties.class})
    static class Config { }

    /** Context web dùng gửi yêu cầu qua filter bảo mật. */
    @Autowired private WebApplicationContext context;
    /** Entity manager để tạo fixture rollback sau mỗi test. */
    @PersistenceContext private EntityManager entityManager;
    /** Repository thật dùng kiểm tra số lượng phân công. */
    @Autowired private LecturerSubjectAssignmentRepository assignments;
    /** Chỉ giả lập decoder, không mock service hoặc repository. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Bộ gửi HTTP trong bộ nhớ. */
    private MockMvc mvc;
    /** Admin thực hiện phân công. */
    private User admin;
    /** Giảng viên chính trong test. */
    private User lecturer;
    /** Giảng viên khác để kiểm tra cách ly dữ liệu. */
    private User colleague;
    /** Sinh viên không được sử dụng các API này. */
    private User student;
    /** Môn thứ nhất. */
    private Subject first;
    /** Môn thứ hai. */
    private Subject second;
    /** Đường dẫn chung của bốn API. */
    private static final String URL = "/lecturer-subject-assignments";

    /** Tạo dữ liệu mới cho mỗi bài kiểm tra; không kết nối database bên ngoài. */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Role lecturerRole = role("LECTURER");
        admin = user("admin", role("ADMIN"));
        lecturer = user("lecturer", lecturerRole);
        colleague = user("colleague", lecturerRole);
        student = user("student", role("STUDENT"));
        first = subject("AAA");
        second = subject("BBB");
        entityManager.flush();
    }

    /** Một giảng viên nhận nhiều môn, một môn nhận nhiều giảng viên; gửi lặp không tạo trùng. */
    @Test
    void assignsManyToManyIdempotentlyAndPreservesOtherAssignments() throws Exception {
        change("", lecturer, first.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changedSubjectIds.length()").value(1));
        change("", lecturer, first.getId(), second.getId(), second.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changedSubjectIds[0]").value(second.getId()))
                .andExpect(jsonPath("$.result.unchangedSubjectIds[0]").value(first.getId()));
        change("", lecturer, second.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changedSubjectIds").isEmpty());
        change("", colleague, first.getId()).andExpect(status().isOk());
        assertThat(assignments.count()).isEqualTo(3);
    }

    /** Thu hồi lặp trả thành công và giữ nguyên phân công ngoài phạm vi yêu cầu. */
    @Test
    void revokesIdempotentlyWithoutTouchingOtherAssignments() throws Exception {
        seed(lecturer, first);
        seed(lecturer, second);
        seed(colleague, first);
        change("/revocations", lecturer, first.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("kể cả câu hỏi của mình")));
        change("/revocations", lecturer, first.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changedSubjectIds").isEmpty())
                .andExpect(jsonPath("$.result.unchangedSubjectIds[0]").value(first.getId()));
        assertThat(assignments.existsByLecturerIdAndSubjectId(lecturer.getId(), second.getId())).isTrue();
        assertThat(assignments.existsByLecturerIdAndSubjectId(colleague.getId(), first.getId())).isTrue();
        assertThat(assignments.count()).isEqualTo(2);
    }

    /** Một môn không tồn tại làm cả lô thêm/thu hồi thất bại trước khi ghi. */
    @ParameterizedTest
    @ValueSource(strings = {"", "/revocations"})
    void missingSubjectDoesNotPartiallyApply(String suffix) throws Exception {
        seed(lecturer, first);
        change(suffix, lecturer, first.getId(), second.getId(), Long.MAX_VALUE)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1016));
        assertThat(assignments.count()).isEqualTo(1);
        assertThat(assignments.existsByLecturerIdAndSubjectId(lecturer.getId(), first.getId())).isTrue();
    }

    /** User không tồn tại trả 404 cho cả hai thao tác. */
    @ParameterizedTest
    @ValueSource(strings = {"", "/revocations"})
    void rejectsMissingLecturer(String suffix) throws Exception {
        User missing = User.builder().id(Long.MAX_VALUE).build();
        change(suffix, missing, first.getId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1006));
        assertThat(assignments.count()).isZero();
    }

    /** Không phân công hoặc thu hồi qua tài khoản đích có vai trò khác LECTURER. */
    @ParameterizedTest
    @ValueSource(strings = {"", "/revocations"})
    void rejectsNonLecturerTarget(String suffix) throws Exception {
        change(suffix, student, first.getId()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1200));
        change(suffix, admin, first.getId()).andExpect(status().isBadRequest());
    }

    /** Admin xem tất cả hoặc lọc theo giảng viên, phân trang không lộ trường mật. */
    @Test
    void adminCanListAllAndFilterWithPagination() throws Exception {
        seed(lecturer, first);
        seed(lecturer, second);
        seed(colleague, first);
        mvc.perform(as(get(URL).param("size", "1"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(3))
                .andExpect(jsonPath("$.result.items.length()").value(1))
                .andExpect(jsonPath("$.result.items[0].lecturerUsername").value("lecturer"))
                .andExpect(jsonPath("$.result.items[0].passwordHash").doesNotExist());
        mvc.perform(as(get(URL).param("lecturerId", colleague.getId().toString()), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalElements").value(1));
        mvc.perform(as(get(URL).param("lecturerId", "9223372036854775807"), admin))
                .andExpect(status().isNotFound());
    }

    /** Dropdown chỉ chứa môn của người đăng nhập, kể cả client cố gửi ID người khác. */
    @Test
    void lecturerCanOnlySeeOwnSubjectsAndEmptyListIsValid() throws Exception {
        seed(lecturer, first);
        seed(colleague, second);
        mvc.perform(as(get(URL + "/my-subjects").param("lecturerId", colleague.getId().toString()), lecturer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.items[0].id").value(first.getId()))
                .andExpect(jsonPath("$.result.items[0].code").value("AAA"));
        change("/revocations", lecturer, first.getId()).andExpect(status().isOk());
        mvc.perform(as(get(URL + "/my-subjects"), lecturer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items").isEmpty());
    }

    /** Lecturer không gọi được API admin, student không gọi được cả bốn API. */
    @Test
    void enforcesRoleMatrixAndAuthentication() throws Exception {
        for (User actor : List.of(lecturer, student)) {
            mvc.perform(as(get(URL), actor)).andExpect(status().isForbidden());
            for (String suffix : List.of("", "/revocations")) {
                mvc.perform(as(post(URL + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content(body(lecturer, first.getId())), actor)).andExpect(status().isForbidden());
            }
        }
        for (User actor : List.of(admin, student)) {
            mvc.perform(as(get(URL + "/my-subjects"), actor)).andExpect(status().isForbidden());
        }
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/my-subjects")).andExpect(status().isUnauthorized());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(lecturer, first.getId())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(URL + "/revocations").contentType(MediaType.APPLICATION_JSON).content(body(lecturer, first.getId())))
                .andExpect(status().isUnauthorized());
        assertThat(assignments.count()).isZero();
    }

    /** Token cũ mang ADMIN không vượt qua vai trò hiện hành trong database. */
    @Test
    void staleAdminRoleCannotReadOrWrite() throws Exception {
        mvc.perform(get(URL).with(jwt().jwt(j -> j.subject(student.getUsername()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(lecturer, first.getId()))
                .with(jwt().jwt(j -> j.subject(student.getUsername())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        assertThat(assignments.count()).isZero();
    }

    /** Token hợp lệ về hình thức nhưng tài khoản đã bị xóa không được truy cập. */
    @Test
    void deletedAccountIsUnauthenticated() throws Exception {
        mvc.perform(get(URL + "/my-subjects").with(jwt().jwt(j -> j.subject("missing"))
                .authorities(new SimpleGrantedAuthority("ROLE_LECTURER"))))
                .andExpect(status().isUnauthorized());
    }

    /** JSON thiếu trường, danh sách rỗng hoặc ID sai đều trả 400 trên cả hai POST. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{invalid", "{\"lecturerId\":0,\"subjectIds\":[1]}",
            "{\"lecturerId\":1,\"subjectIds\":[]}", "{\"lecturerId\":1,\"subjectIds\":[null]}",
            "{\"lecturerId\":1,\"subjectIds\":[-1]}", "{\"lecturerId\":1,\"subjectIds\":null}"})
    void rejectsInvalidBody(String body) throws Exception {
        for (String suffix : List.of("", "/revocations")) {
            mvc.perform(as(post(URL + suffix).contentType(MediaType.APPLICATION_JSON).content(body), admin))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1002));
        }
    }

    /** Không nhận quá 100 mã môn trong một lô. */
    @Test
    void rejectsOversizedBatch() throws Exception {
        Long[] ids = java.util.Collections.nCopies(101, first.getId()).toArray(Long[]::new);
        change("", lecturer, ids).andExpect(status().isBadRequest());
        change("/revocations", lecturer, ids).andExpect(status().isBadRequest());
    }

    /** Tham số phân trang và bộ lọc phải hợp lệ, không trả 500 do lỗi binding. */
    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?page=1000001", "?size=0", "?size=101", "?page=abc"})
    void rejectsInvalidPagination(String query) throws Exception {
        mvc.perform(as(get(URL + query), admin)).andExpect(status().isBadRequest());
        mvc.perform(as(get(URL + "/my-subjects" + query), lecturer)).andExpect(status().isBadRequest());
    }

    /** ID bộ lọc admin không được âm hoặc sai kiểu. */
    @Test
    void rejectsInvalidLecturerFilter() throws Exception {
        mvc.perform(as(get(URL).param("lecturerId", "-1"), admin)).andExpect(status().isBadRequest());
        mvc.perform(as(get(URL).param("lecturerId", "abc"), admin)).andExpect(status().isBadRequest());
    }

    /** Thu hồi làm chủ câu hỏi mất quyền xem nhưng bản ghi câu hỏi vẫn tồn tại. */
    @Test
    void revocationRemovesQuestionAccessWithoutDeletingContent() throws Exception {
        seed(lecturer, first);
        String result = mvc.perform(as(post("/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + first.getId() + ",\"content\":\"Câu hỏi cần giữ\"}"), lecturer))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number id = com.jayway.jsonpath.JsonPath.read(result, "$.result.id");
        change("/revocations", lecturer, first.getId()).andExpect(status().isOk());
        mvc.perform(as(get("/questions/" + id), lecturer)).andExpect(status().isNotFound());
        assertThat(entityManager.find(Question.class, id.longValue()).getContent()).isEqualTo("Câu hỏi cần giữ");
        mvc.perform(as(get("/questions/" + id), admin)).andExpect(status().isOk());
    }

    /** Gửi yêu cầu thay đổi dưới quyền admin thật trong fixture. */
    private org.springframework.test.web.servlet.ResultActions change(String suffix, User target, Long... ids) throws Exception {
        return mvc.perform(as(post(URL + suffix).contentType(MediaType.APPLICATION_JSON).content(body(target, ids)), admin));
    }

    /** Tạo JSON chỉ chứa dữ liệu được phép thay đổi. */
    private String body(User target, Long... ids) {
        return "{\"lecturerId\":" + target.getId() + ",\"subjectIds\":" + List.of(ids) + "}";
    }

    /** Giả lập JWT qua filter thật với subject là username như cơ chế đăng nhập hiện tại. */
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, User actor) {
        return request.with(jwt().jwt(j -> j.subject(actor.getUsername()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + actor.getRole().getCode())));
    }

    /** Tạo vai trò độc lập trong transaction test. */
    private Role role(String code) {
        Role role = Role.builder().code(code).name(code).build();
        entityManager.persist(role);
        return role;
    }

    /** Tạo tài khoản có role rõ ràng. */
    private User user(String name, Role role) {
        User user = User.builder().username(name).fullName(name).email(name + "@test.invalid").role(role).build();
        entityManager.persist(user);
        return user;
    }

    /** Tạo môn học với mã duy nhất. */
    private Subject subject(String code) {
        Subject subject = Subject.builder().code(code).name("Môn " + code).build();
        entityManager.persist(subject);
        return subject;
    }

    /** Chuẩn bị phân công sẵn có để kiểm tra bổ sung và thu hồi. */
    private void seed(User lecturer, Subject subject) {
        assignments.saveAndFlush(LecturerSubjectAssignment.builder().lecturer(lecturer).subject(subject).build());
    }
}
