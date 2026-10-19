package com.avies.backend.exam;

import com.avies.backend.config.*;
import com.avies.backend.controller.ExamSessionController;
import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.*;
import com.avies.backend.entity.enums.ExamSessionStatus;
import com.avies.backend.exception.*;
import com.avies.backend.service.ExamSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Kiểm tra hợp đồng HTTP, JSON, Bean Validation và method security; không kết nối database/AI. */
@SpringJUnitConfig(ExamSessionControllerTest.Config.class)
@WebAppConfiguration
class ExamSessionControllerTest {
    /** Chỉ tải controller, handler và filter chain bảo mật hiện có. */
    @Configuration
    @EnableWebMvc
    @Import({ExamSessionController.class, ExamSessionExceptionHandler.class, SecurityConfig.class, CorsProperties.class})
    static class Config { }
    /** Context để dựng MockMvc với filter bảo mật. */
    @Autowired private WebApplicationContext context;
    /** Service giả lập để xác nhận request sai không chạm tầng nghiệp vụ. */
    @MockitoBean private ExamSessionService service;
    /** Không đọc signer key hoặc khởi tạo dịch vụ xác thực thật. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Bộ gửi HTTP trong bộ nhớ. */
    private MockMvc mvc;

    /** Tạo web client với Spring Security thật. */
    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** Tạo thành công trả 201/Location, giữ tiếng Việt và không nhận chủ sở hữu từ client. */
    @Test
    void creates201WithLocationAndDocumentedResponse() throws Exception {
        when(service.create(any())).thenReturn(response());
        mvc.perform(post("/exam-sessions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER")))
                .contentType(MediaType.APPLICATION_JSON).content(validCreate()))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/exam-sessions/7"))
                .andExpect(jsonPath("$.code").value(1000)).andExpect(jsonPath("$.result.status").value("UPCOMING"))
                .andExpect(jsonPath("$.result.title").value("Phiên thi tiếng Việt"))
                .andExpect(jsonPath("$.result.finalScore").doesNotExist());
        verify(service).create(argThat(request -> request.getScheduledStartAt().getOffset().getTotalSeconds() == 25200
                && request.getSubjectId() == 1L && request.getMaxFollowUps() == 0));
    }

    /** JSON thiếu trường, sai kiểu, timestamp hoặc có mass assignment đều trả 400. */
    @ParameterizedTest
    @MethodSource("invalidCreates")
    void rejectsInvalidCreateWithoutCallingService(String body) throws Exception {
        mvc.perform(post("/exam-sessions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(service);
    }

    /** JSON PATCH rỗng/null/trường cấm không được đưa vào cập nhật. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{broken", "null", "[]", "{\"title\":null}", "{\"scheduledStartAt\":null}",
            "{\"scheduledEndAt\":null}", "{\"slotDurationMinutes\":null}", "{\"maxMainQuestions\":null}",
            "{\"maxFollowUps\":null}", "{\"slotDurationMinutes\":0}", "{\"slotDurationMinutes\":-1}",
            "{\"maxMainQuestions\":0}", "{\"maxFollowUps\":-1}", "{\"maxFollowUps\":1.5}",
            "{\"maxMainQuestions\":\"3\"}", "{\"title\":false}", "{\"subjectId\":1}",
            "{\"lecturerId\":1}", "{\"status\":\"COMPLETED\"}", "{\"createdAt\":\"2030-01-01T00:00:00Z\"}",
            "{\"id\":1}", "{\"finalScore\":10}", "{\"scheduledStartAt\":\"2030-01-01T09:00:00\"}"
    })
    void rejectsInvalidPatchWithoutCallingService(String body) throws Exception {
        mvc.perform(patch("/exam-sessions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(service);
    }

    /** PATCH hợp lệ chỉ đánh dấu trường được gửi, kể cả maxFollowUps bằng không. */
    @Test
    void patchPreservesOmittedFieldsAndSupportsZeroFollowUps() throws Exception {
        when(service.update(eq(7L), any())).thenReturn(response());
        mvc.perform(patch("/exam-sessions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxFollowUps\":0}"))
                .andExpect(status().isOk());
        verify(service).update(eq(7L), argThat(request -> request.isMaxFollowUpsPresent()
                && !request.isScheduledStartAtPresent() && !request.isTitlePresent() && request.getMaxFollowUps() == 0));
    }

    /** Lecturer và admin đều đọc được endpoint; lọc sở hữu tiếp tục được kiểm tra ở service. */
    @ParameterizedTest
    @ValueSource(strings = {"LECTURER", "ADMIN"})
    void readerRolesCanListAndRead(String role) throws Exception {
        when(service.get(7L)).thenReturn(response());
        when(service.search(any())).thenReturn(PageResponse.<ExamSessionResponse>builder()
                .items(List.of()).page(0).size(20).totalElements(0).totalPages(0).build());
        mvc.perform(get("/exam-sessions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isOk());
        mvc.perform(get("/exam-sessions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items").isEmpty())
                .andExpect(jsonPath("$.result.page").value(0)).andExpect(jsonPath("$.result.size").value(20))
                .andExpect(jsonPath("$.result.totalElements").value(0)).andExpect(jsonPath("$.result.totalPages").value(0));
    }

    /** Admin chỉ đọc; student không ghi được bất kỳ endpoint nào. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "STUDENT"})
    void disallowedRolesCannotWrite(String role) throws Exception {
        var token = jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
        mvc.perform(post("/exam-sessions").with(token).contentType(MediaType.APPLICATION_JSON).content(validCreate()))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/exam-sessions/7").with(token).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Mới\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/exam-sessions/7").with(token)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    /** Student và request không xác thực không sử dụng được các API đọc. */
    @Test
    void studentAndAnonymousAreDenied() throws Exception {
        mvc.perform(get("/exam-sessions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/exam-sessions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/exam-sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/exam-sessions/7")).andExpect(status().isUnauthorized());
        mvc.perform(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(validCreate()))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/exam-sessions/7").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Mới\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/exam-sessions/7")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    /** URL ID phải dương và đúng kiểu trên tất cả endpoint nhận ID. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidPathIds(String id) throws Exception {
        var token = jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER"));
        mvc.perform(get("/exam-sessions/" + id).with(token)).andExpect(status().isBadRequest());
        mvc.perform(patch("/exam-sessions/" + id).with(token).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Mới\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/exam-sessions/" + id).with(token)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    /** Phân trang và filter sai enum/ID phải trả 400, không chạy query. */
    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=1000001", "size=0", "size=101", "size=x", "page=1.5",
            "status=CANCELLED", "status=upcoming", "subjectId=0", "subjectId=-1", "subjectId=x"})
    void rejectsInvalidSearch(String query) throws Exception {
        mvc.perform(get("/exam-sessions?" + query).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(service);
    }

    /** DELETE thành công giữ envelope API hiện có và trả HTTP 200. */
    @Test
    void deleteReturns200() throws Exception {
        mvc.perform(delete("/exam-sessions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(service).delete(7L);
    }

    /** Lỗi khóa và lỗi nội bộ không làm rò SQL/password trong response. */
    @Test
    void errorsAreControlledAndDoNotLeakInternalDetails() throws Exception {
        when(service.update(eq(7L), any())).thenThrow(new CannotAcquireLockException("secret SQL aives.exam_sessions"));
        mvc.perform(patch("/exam-sessions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Mới\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1303))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
        when(service.get(7L)).thenThrow(new IllegalStateException("password=do-not-expose"));
        mvc.perform(get("/exam-sessions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER"))))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
    }

    /** Bộ dữ liệu JSON sai bao phủ trường bắt buộc, số lẻ/overflow, timezone và mass assignment. */
    static Stream<String> invalidCreates() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var bodies = new java.util.ArrayList<String>(List.of("{}", "null", "[]", "{broken"));
        for (String field : List.of("title", "subjectId", "scheduledStartAt", "scheduledEndAt", "slotDurationMinutes", "maxMainQuestions", "maxFollowUps")) {
            ObjectNode omitted = (ObjectNode) mapper.readTree(validCreate());
            omitted.remove(field);
            bodies.add(omitted.toString());
            ObjectNode nullValue = (ObjectNode) mapper.readTree(validCreate());
            nullValue.putNull(field);
            bodies.add(nullValue.toString());
        }
        for (String patch : List.of("{\"title\":\" \"}", "{\"title\":3}", "{\"subjectId\":1.5}",
                "{\"subjectId\":0}", "{\"slotDurationMinutes\":0}", "{\"slotDurationMinutes\":-1}",
                "{\"maxMainQuestions\":0}", "{\"maxFollowUps\":-1}", "{\"maxFollowUps\":2.9}",
                "{\"maxMainQuestions\":2147483648}", "{\"slotDurationMinutes\":\"20\"}",
                "{\"scheduledStartAt\":\"2030-01-01T09:00:00\"}", "{\"scheduledEndAt\":123}",
                "{\"lecturerId\":1}", "{\"status\":\"ONGOING\"}", "{\"finalScore\":10}")) {
            ObjectNode changed = (ObjectNode) mapper.readTree(validCreate());
            changed.setAll((ObjectNode) mapper.readTree(patch));
            bodies.add(changed.toString());
        }
        ObjectNode tooLong = (ObjectNode) mapper.readTree(validCreate());
        tooLong.put("title", "a".repeat(256));
        bodies.add(tooLong.toString());
        return bodies.stream();
    }

    /** Request đầy đủ cho test biên HTTP; kiểm tra tương lai thuộc test service thật. */
    private static String validCreate() {
        return """
                {"title":"Phiên thi tiếng Việt","subjectId":1,"scheduledStartAt":"2030-01-01T09:00:00+07:00",
                "scheduledEndAt":"2030-01-01T11:00:00+07:00","slotDurationMinutes":20,"maxMainQuestions":3,"maxFollowUps":0}
                """;
    }

    /** DTO tối thiểu để kiểm tra envelope mà không trả entity JPA. */
    private ExamSessionResponse response() {
        return ExamSessionResponse.builder().id(7L).title("Phiên thi tiếng Việt").status(ExamSessionStatus.UPCOMING).build();
    }
}
