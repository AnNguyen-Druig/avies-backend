package com.avies.backend.exam;

import com.avies.backend.config.*;
import com.avies.backend.controller.*;
import com.avies.backend.dto.response.*;
import com.avies.backend.exception.exceptions.ExamSessionExceptionHandler;
import com.avies.backend.service.StudentExamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Hợp đồng HTTP và phân quyền 5 endpoint; mock service, không mở datasource hoặc đọc secrets. */
@SpringJUnitConfig(StudentAssignmentControllerTest.Config.class)
@WebAppConfiguration
class StudentAssignmentControllerTest {
    /** Context web tối thiểu với filter và method security thật. */
    @Configuration
    @EnableWebMvc
    @Import({EligibleStudentController.class, StudentAssignmentController.class, StudentExamController.class,
            ExamSessionExceptionHandler.class, SecurityConfig.class, CorsProperties.class})
    static class Config { }
    /** Context để dựng client HTTP trong bộ nhớ. */
    @Autowired private WebApplicationContext context;
    /** Không chạy nghiệp vụ DB trong nhóm test hợp đồng. */
    @MockitoBean private StudentExamService service;
    /** Không khởi tạo AuthenticationService hoặc dùng signer key thật. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Client có filter chain thật. */
    private MockMvc mvc;

    /** Dựng client cho mỗi ca, Mockito reset bean giữa các ca bởi Spring. */
    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** Batch phản hồi 200 cho cả created và unchanged theo contract idempotency. */
    @Test
    void assignmentReturns200AndDoesNotAcceptClientOwner() throws Exception {
        when(service.assign(eq(7L), any())).thenReturn(StudentAssignmentResponse.builder()
                .sessionId(7L).created(List.of()).unchanged(List.of()).build());
        mvc.perform(actor(post("/exam-sessions/7/students").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentIds\":[4,4,6]}"), "LECTURER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.created").isArray()).andExpect(jsonPath("$.result.unchanged").isArray());
        verify(service).assign(eq(7L), argThat(r -> r.getStudentIds().equals(List.of(4L, 4L, 6L))));
    }

    /** Kiểu dữ liệu, ID, null và field ngoài contract bị chặn trước service. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"studentIds\":[]}", "{\"studentIds\":null}", "{\"studentIds\":[null]}",
            "{\"studentIds\":[0]}", "{\"studentIds\":[-1]}", "{\"studentIds\":[\"4\"]}",
            "{\"studentIds\":[4.0]}", "{\"studentIds\":[true]}", "{\"studentIds\":[9223372036854775808]}",
            "{\"studentIds\":[4],\"lecturerId\":1}", "{\"studentIds\":[4],\"scheduledStartAt\":null}"})
    void rejectsInvalidBatchBeforeService(String body) throws Exception {
        mvc.perform(actor(post("/exam-sessions/7/students").contentType(MediaType.APPLICATION_JSON).content(body), "LECTURER"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(service);
    }

    /** Batch không vượt 100 phần tử ngay cả khi tất cả ID giống nhau. */
    @Test
    void limitsRawBatchSize() throws Exception {
        String body = "{\"studentIds\":[" + String.join(",", java.util.Collections.nCopies(101, "4")) + "]}";
        mvc.perform(actor(post("/exam-sessions/7/students").contentType(MediaType.APPLICATION_JSON).content(body), "LECTURER"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    /** Cả năm API đều cần xác thực, không chỉ các thao tác ghi. */
    @Test
    void allEndpointsRequireAuthentication() throws Exception {
        for (var request : List.of(get("/students/eligible"), get("/exam-sessions/7/students"),
                post("/exam-sessions/7/students").contentType(MediaType.APPLICATION_JSON).content("{\"studentIds\":[4]}"),
                delete("/student-exams/8"), get("/student-exams/me"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
    }

    /** ADMIN và STUDENT không được tra cứu sinh viên hoặc gán/thu hồi thay lecturer. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "STUDENT"})
    void rejectsNonLecturerWritesAndDirectory(String role) throws Exception {
        mvc.perform(actor(get("/students/eligible"), role)).andExpect(status().isForbidden());
        mvc.perform(actor(post("/exam-sessions/7/students").contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentIds\":[4]}"), role)).andExpect(status().isForbidden());
        mvc.perform(actor(delete("/student-exams/8"), role)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    /** Lịch cá nhân không mở cho ADMIN/LECTURER; danh sách lớp không mở cho STUDENT. */
    @Test
    void protectsStudentOnlyAndLecturerOnlyReadBoundaries() throws Exception {
        mvc.perform(actor(get("/student-exams/me"), "LECTURER")).andExpect(status().isForbidden());
        mvc.perform(actor(get("/student-exams/me"), "ADMIN")).andExpect(status().isForbidden());
        mvc.perform(actor(get("/exam-sessions/7/students"), "STUDENT")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    /** Quyền ADMIN chỉ đọc; envelope phân trang không phụ thuộc cấu trúc PageImpl. */
    @Test
    void returnsDocumentedReadEnvelopesAndPageDefaults() throws Exception {
        when(service.eligible(any())).thenReturn(page());
        when(service.assigned(eq(7L), any())).thenReturn(page());
        when(service.mine(any())).thenReturn(page());
        mvc.perform(actor(get("/students/eligible").param("query", "Lan"), "LECTURER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items").isEmpty())
                .andExpect(jsonPath("$.result.size").value(20));
        mvc.perform(actor(get("/exam-sessions/7/students"), "ADMIN")).andExpect(status().isOk());
        mvc.perform(actor(get("/student-exams/me"), "STUDENT")).andExpect(status().isOk());
        verify(service).eligible(argThat(r -> r.getQuery().equals("Lan") && r.getPage() == 0 && r.getSize() == 20));
    }

    /** Cả ba GET dùng cùng giới hạn phân trang để query/count không nhận offset sai. */
    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=1000001", "size=abc"})
    void rejectsInvalidPaginationOnEveryList(String query) throws Exception {
        mvc.perform(actor(get("/students/eligible?" + query), "LECTURER")).andExpect(status().isBadRequest());
        mvc.perform(actor(get("/exam-sessions/7/students?" + query), "LECTURER")).andExpect(status().isBadRequest());
        mvc.perform(actor(get("/student-exams/me?" + query), "STUDENT")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    /** Từ khóa quá dài và ID không dương phải bị validation từ chối. */
    @Test
    void rejectsLongQueryAndInvalidPathIds() throws Exception {
        mvc.perform(actor(get("/students/eligible").param("query", "a".repeat(101)), "LECTURER")).andExpect(status().isBadRequest());
        mvc.perform(actor(get("/exam-sessions/0/students"), "LECTURER")).andExpect(status().isBadRequest());
        mvc.perform(actor(delete("/student-exams/0"), "LECTURER")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    /** DELETE thành công trả 200 envelope và không trả entity. */
    @Test
    void deletionReturnsMessageNotEntity() throws Exception {
        mvc.perform(actor(delete("/student-exams/8"), "LECTURER")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000)).andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(service).remove(8L);
    }

    /** Lỗi khóa/SQL không bao giờ lộ chi tiết cho client. */
    @Test
    void mapsContentionAndUnexpectedErrorsWithoutLeakingInternals() throws Exception {
        doThrow(new CannotAcquireLockException("SQL aives.private secret")).when(service).remove(8L);
        mvc.perform(actor(delete("/student-exams/8"), "LECTURER")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1303)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
        when(service.assigned(eq(7L), any())).thenThrow(new IllegalStateException("SQL aives.private secret"));
        mvc.perform(actor(get("/exam-sessions/7/students"), "LECTURER")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(9999)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    /** Quyền JWT chỉ dùng ở boundary; role DB còn được kiểm tra bởi service trong nhóm PostgreSQL. */
    private MockHttpServletRequestBuilder actor(MockHttpServletRequestBuilder request, String role) {
        return request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    /** Trang rỗng có đủ metadata theo PageResponse dùng chung. */
    private <T> PageResponse<T> page() {
        return PageResponse.<T>builder().items(List.of()).page(0).size(20).totalElements(0).totalPages(0).build();
    }
}
