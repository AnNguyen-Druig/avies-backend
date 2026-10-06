package com.avies.backend.question;

import com.avies.backend.config.*;
import com.avies.backend.controller.QuestionController;
import com.avies.backend.dto.request.QuestionUpdateRequest;
import com.avies.backend.dto.response.*;
import com.avies.backend.exception.*;
import com.avies.backend.service.QuestionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Kiểm tra HTTP, JSON, validation và SecurityConfig thật mà không khởi động database hoặc AI. */
@SpringJUnitConfig(QuestionControllerTest.WebConfig.class)
@WebAppConfiguration
class QuestionControllerTest {
    /** Ngữ cảnh web tối thiểu dùng filter bảo mật thật của ứng dụng. */
    @Configuration
    @EnableWebMvc
    @Import({QuestionController.class, QuestionExceptionHandler.class, SecurityConfig.class, CorsProperties.class})
    static class WebConfig { }

    /** Ngữ cảnh dùng dựng MockMvc có Spring Security. */
    @Autowired private WebApplicationContext context;
    /** Dịch vụ giả lập để tập trung kiểm tra biên HTTP. */
    @MockitoBean private QuestionService service;
    /** Decoder giả lập để không cần khóa bí mật hoặc gọi dịch vụ xác thực thật. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Bộ gửi yêu cầu HTTP trong bộ nhớ. */
    private MockMvc mvc;

    /** Tạo MockMvc với filter chain thật trước mỗi bài kiểm tra. */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** POST trả 201, Location và dữ liệu theo envelope hiện hành. */
    @Test
    void createsWithLocationAndUtf8Payload() throws Exception {
        when(service.createQuestion(any())).thenReturn(QuestionResponse.builder().id(7L).content("Câu hỏi tiếng Việt").build());
        mvc.perform(post("/questions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER")))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"subjectId":1,"content":"Câu hỏi tiếng Việt","topics":["Mảng"]}
                                """))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/questions/7"))
                .andExpect(jsonPath("$.result.content").value("Câu hỏi tiếng Việt"));
    }

    /** Tài khoản chưa xác thực không thể đọc ngân hàng câu hỏi. */
    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mvc.perform(get("/questions")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    /** Student bị chặn tại method security trước khi service được gọi. */
    @Test
    void studentCannotReadOrWriteQuestions() throws Exception {
        mvc.perform(get("/questions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/questions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STUDENT")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":1,\"content\":\"Câu hỏi\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    /** Các request không hợp lệ phải trả 400 trước khi chạm service. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"subjectId\":1,\"content\":\" \"}",
            "{\"subjectId\":0,\"content\":\"Câu hỏi\"}",
            "{\"subjectId\":1,\"content\":\"Câu hỏi\",\"answerTimeLimitSeconds\":0}",
            "{\"subjectId\":1,\"content\":\"Câu hỏi\",\"topics\":[null]}",
            "{\"subjectId\":1,\"content\":\"Câu hỏi\",\"topics\":null}",
            "{\"subjectId\":1,\"content\":\"Câu hỏi\",\"bloomLevel\":\"CREATE\"}",
            "{\"subjectId\":1,\"content\":\"Câu hỏi\",\"status\":\"APPROVED\"}",
            "{\"subjectId\":1,\"content\":\"Câu hỏi\",\"createdById\":99}",
            "{\"subjectId\":1,\"content\":\"Câu hỏi\",\"sourceType\":\"AI\"}", "{invalid"
    })
    void rejectsInvalidCreateAndMassAssignment(String body) throws Exception {
        mvc.perform(post("/questions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(service);
    }

    /** PATCH phân biệt rõ không gửi content và gửi referenceAnswer bằng null. */
    @Test
    void patchPreservesPresenceInformation() throws Exception {
        when(service.updateQuestion(eq(7L), any())).thenReturn(QuestionResponse.builder().id(7L).build());
        mvc.perform(patch("/questions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER")))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedVersion":0,"referenceAnswer":null,"topics":[]}
                                """))
                .andExpect(status().isOk());
        ArgumentCaptor<QuestionUpdateRequest> captor = ArgumentCaptor.forClass(QuestionUpdateRequest.class);
        verify(service).updateQuestion(eq(7L), captor.capture());
        assertThat(captor.getValue().isContentPresent()).isFalse();
        assertThat(captor.getValue().isReferenceAnswerPresent()).isTrue();
        assertThat(captor.getValue().getReferenceAnswer()).isNull();
        assertThat(captor.getValue().isTopicsPresent()).isTrue();
    }

    /** PATCH thiếu version, rỗng hoặc xóa trường bắt buộc phải bị từ chối. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"content\":\"Mới\"}", "{\"expectedVersion\":0}",
            "{\"expectedVersion\":0,\"content\":null}", "{\"expectedVersion\":0,\"content\":\" \"}",
            "{\"expectedVersion\":0,\"topics\":null}",
            "{\"expectedVersion\":0,\"content\":\"Mới\",\"subjectId\":2}"
    })
    void rejectsInvalidPatch(String body) throws Exception {
        mvc.perform(patch("/questions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    /** ID, enum, kích thước trang và tên cột sắp xếp đều được kiểm tra. */
    @ParameterizedTest
    @ValueSource(strings = {"/questions/0", "/questions/-1", "/questions/abc", "/questions?page=-1",
            "/questions?size=101", "/questions?size=0", "/questions?status=BOGUS",
            "/questions?sort=passwordHash,asc", "/questions?page=1000001"})
    void rejectsInvalidPathOrQuery(String url) throws Exception {
        mvc.perform(get(url).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    /** Danh sách dùng cấu trúc phân trang riêng, không lộ cấu trúc Page của framework. */
    @Test
    void returnsStablePaginationEnvelope() throws Exception {
        when(service.searchQuestions(any())).thenReturn(PageResponse.<QuestionSummaryResponse>builder()
                .items(List.of()).page(0).size(20).totalElements(0).totalPages(0).build());
        mvc.perform(get("/questions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items").isArray())
                .andExpect(jsonPath("$.result.totalElements").value(0));
    }

    /** Chi tiết sử dụng đúng đường dẫn và ánh xạ DTO. */
    @Test
    void returnsQuestionDetail() throws Exception {
        when(service.getQuestion(7L)).thenReturn(QuestionResponse.builder().id(7L).version(2L).build());
        mvc.perform(get("/questions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LECTURER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.version").value(2));
    }

    /** DELETE trả kết quả thành công ổn định cho cả yêu cầu lặp lại. */
    @Test
    void deleteCanBeRepeated() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(delete("/questions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1000));
        }
        verify(service, times(2)).deleteQuestion(7L);
    }

    /** Xung đột version được trả rõ ràng bằng HTTP 409. */
    @Test
    void mapsVersionConflictTo409() throws Exception {
        when(service.updateQuestion(eq(7L), any())).thenThrow(new AppException(ErrorCode.QUESTION_VERSION_CONFLICT));
        mvc.perform(patch("/questions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"content\":\"Mới\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1104));
    }

    /** Lỗi nội bộ không được lộ câu SQL hoặc thông tin hạ tầng trong response. */
    @Test
    void doesNotLeakInternalExceptionDetails() throws Exception {
        when(service.getQuestion(7L)).thenThrow(new IllegalStateException("SECRET_SQL_DETAIL"));
        MvcResult result = mvc.perform(get("/questions/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isInternalServerError()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("SECRET_SQL_DETAIL");
    }
}
