package com.avies.backend.material;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.entity.LearningMaterial;
import com.avies.backend.entity.LecturerSubjectAssignment;
import com.avies.backend.entity.Role;
import com.avies.backend.entity.Subject;
import com.avies.backend.entity.User;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.LearningMaterialChunkRepository;
import com.avies.backend.repository.LearningMaterialRepository;
import com.avies.backend.repository.LecturerSubjectAssignmentRepository;
import com.avies.backend.service.DocumentParserService;
import com.avies.backend.service.EmbeddingService;
import com.avies.backend.service.LearningMaterialService;
import com.avies.backend.service.TextChunkingService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Test controller/service/repository thật và file tạm; chỉ giả lập JWT, parser và dịch vụ vector/AI. */
@SpringJUnitConfig(LearningMaterialAccessTestConfig.class)
@WebAppConfiguration
class LearningMaterialAccessIntegrationTest {
    /** Context web dùng filter bảo mật của ứng dụng. */
    @Autowired private WebApplicationContext context;
    /** Dịch vụ thật để kiểm tra cả lời gọi không đi qua controller. */
    @Autowired private LearningMaterialService service;
    /** Kho metadata thật để kiểm tra dữ liệu không bị xóa khi thu hồi. */
    @Autowired private LearningMaterialRepository materials;
    /** Kho phân công thật trong H2. */
    @Autowired private LecturerSubjectAssignmentRepository assignments;
    /** Transaction dùng chuẩn bị fixture và xác nhận dữ liệu giữa các request. */
    @Autowired private PlatformTransactionManager transactionManager;
    /** Entity manager dùng tạo các tài khoản, môn và tài liệu mẫu. */
    @PersistenceContext private EntityManager entityManager;
    /** Decoder giả lập, không dùng khóa/token thật. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Không đọc tài liệu qua parser bên ngoài trong test phân quyền. */
    @MockitoBean private DocumentParserService parser;
    /** Tách chunks giả lập để giữ nguyên luồng upload hợp lệ. */
    @MockitoBean private TextChunkingService chunking;
    /** Không gọi OpenAI hoặc nhà cung cấp AI thật. */
    @MockitoBean private EmbeddingService embeddings;
    /** Chỉ giả lập batch ghi vector; metadata và phân công vẫn lưu bằng JPA thật. */
    @MockitoBean private JdbcTemplate vectorJdbc;
    /** Dependency chunks hiện có không được truy cập khi bị từ chối quyền. */
    @MockitoBean private LearningMaterialChunkRepository chunks;
    /** Mọi file upload chỉ được lưu trong thư mục tạm do JUnit tự dọn. */
    @TempDir private Path storage;
    /** Bộ gửi HTTP trong bộ nhớ. */
    private MockMvc mvc;
    /** Quản trị viên không cần phân công môn. */
    private User admin;
    /** Giảng viên được phân công môn chính. */
    private User lecturer;
    /** Giảng viên khác chưa được phân công môn chính. */
    private User outsider;
    /** Sinh viên không được sử dụng API tài liệu. */
    private User student;
    /** Môn có phân công cho lecturer. */
    private Subject subject;
    /** Môn khác tồn tại nhưng không được phân công cho lecturer. */
    private Subject otherSubject;
    /** Tài liệu do lecturer tạo để kiểm tra quyền của chủ sở hữu sau thu hồi. */
    private Long materialId;

    /** Chuẩn bị fixture đã commit; không dùng transaction bao ngoài toàn bộ test. */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ReflectionTestUtils.setField(service, "storageLocation", storage.toString());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("delete from LearningMaterial").executeUpdate();
            entityManager.createQuery("delete from LecturerSubjectAssignment").executeUpdate();
            entityManager.createQuery("delete from User").executeUpdate();
            entityManager.createQuery("delete from Subject").executeUpdate();
            entityManager.createQuery("delete from Role").executeUpdate();
            Role lecturerRole = role("LECTURER");
            admin = user("admin", role("ADMIN"));
            lecturer = user("lecturer", lecturerRole);
            outsider = user("outsider", lecturerRole);
            student = user("student", role("STUDENT"));
            subject = subject("SWD392");
            otherSubject = subject("OTHER");
            entityManager.persist(LecturerSubjectAssignment.builder().lecturer(lecturer).subject(subject).build());
            LearningMaterial material = LearningMaterial.builder().subject(subject).uploadedBy(lecturer)
                    .title("Tài liệu giữ nguyên").fileName("lecture.txt").processingStatus("READY").build();
            entityManager.persist(material);
            materialId = material.getId();
        });
    }

    /** Không để lời gọi service trực tiếp lưu lại authentication cho test khác. */
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** Giảng viên còn phân công xem được tài liệu và giữ nguyên cấu trúc danh sách hiện có. */
    @Test
    void assignedLecturerCanReadExistingListShape() throws Exception {
        mvc.perform(as(get("/materials/subject/" + subject.getId()), lecturer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result[0].id").value(materialId))
                .andExpect(jsonPath("$.result[0].title").value("Tài liệu giữ nguyên"));
    }

    /** Quyền đọc theo môn không bị đổi thành chỉ xem tài liệu của chính người upload. */
    @Test
    void assignedColleagueCanReadAnotherUploadersMaterial() throws Exception {
        assignments.saveAndFlush(LecturerSubjectAssignment.builder().lecturer(outsider).subject(subject).build());
        mvc.perform(as(get("/materials/subject/" + subject.getId()), outsider))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result[0].id").value(materialId));
    }

    /** Giảng viên ngoài môn bị chặn ngay cả khi biết ID môn và gọi trực tiếp endpoint. */
    @Test
    void unassignedLecturerCannotReadOrUpload() throws Exception {
        mvc.perform(as(get("/materials/subject/" + subject.getId()), outsider))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        mvc.perform(as(upload(subject, validFile()), outsider))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        assertNoUploadSideEffects();
    }

    /** Phân công ở môn A không cho phép upload hoặc đọc tài liệu môn B. */
    @Test
    void assignmentToAnotherSubjectDoesNotGrantAccess() throws Exception {
        mvc.perform(as(get("/materials/subject/" + otherSubject.getId()), lecturer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        mvc.perform(as(upload(otherSubject, validFile()), lecturer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        assertNoUploadSideEffects();
    }

    /** Admin xem mọi môn mà không cần có liên kết phân công. */
    @Test
    void adminCanReadWithoutAssignmentAndEmptySubjectReturnsEmptyList() throws Exception {
        mvc.perform(as(get("/materials/subject/" + subject.getId()), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.length()").value(1));
        mvc.perform(as(get("/materials/subject/" + otherSubject.getId()), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").isEmpty());
    }

    /** Upload hợp lệ của lecturer/admin vẫn chạy pipeline cũ và lưu metadata, file, vector giả lập. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authorizedUploadPreservesPipeline(boolean useAdmin) throws Exception {
        prepareSuccessfulPipeline();
        User actor = useAdmin ? admin : lecturer;
        Subject target = useAdmin ? otherSubject : subject;
        mvc.perform(as(upload(target, validFile()).param("title", "  Giáo trình  "), actor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.subjectId").value(target.getId()))
                .andExpect(jsonPath("$.result.title").value("Giáo trình"))
                .andExpect(jsonPath("$.result.processingStatus").value("READY"))
                .andExpect(jsonPath("$.result.totalChunks").value(1));
        assertThat(materials.count()).isEqualTo(2);
        try (var files = Files.list(storage)) { assertThat(files.count()).isEqualTo(1); }
        verify(parser).extractText(any());
        verify(embeddings).generateEmbeddings(List.of("Nội dung"));
        verify(vectorJdbc).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }

    /** Thu hồi qua API thật có hiệu lực ở request mới, giữ file và metadata của chính lecturer. */
    @Test
    void committedRevocationBlocksFormerUploaderWithoutDeletingData() throws Exception {
        prepareSuccessfulPipeline();
        mvc.perform(as(upload(subject, validFile()), lecturer)).andExpect(status().isOk());
        clearInvocations(parser, chunking, embeddings, vectorJdbc, chunks);
        mvc.perform(as(post("/lecturer-subject-assignments/revocations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lecturerId\":" + lecturer.getId() + ",\"subjectIds\":[" + subject.getId() + "]}"), admin))
                .andExpect(status().isOk());
        mvc.perform(as(get("/materials/subject/" + subject.getId()), lecturer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        mvc.perform(as(upload(subject, validFile()), lecturer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1101));
        verifyNoInteractions(parser, chunking, embeddings, vectorJdbc, chunks);
        assertThat(materials.count()).isEqualTo(2);
        try (var files = Files.list(storage)) { assertThat(files.count()).isEqualTo(1); }
        mvc.perform(as(get("/materials/subject/" + subject.getId()), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.length()").value(2));
        mvc.perform(as(post("/lecturer-subject-assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lecturerId\":" + lecturer.getId() + ",\"subjectIds\":[" + subject.getId() + "]}"), admin))
                .andExpect(status().isOk());
        mvc.perform(as(get("/materials/subject/" + subject.getId()), lecturer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.length()").value(2));
    }

    /** Chưa xác thực không truy cập được cả GET và multipart upload. */
    @Test
    void unauthenticatedHttpRequestsAreRejected() throws Exception {
        mvc.perform(get("/materials/subject/" + subject.getId())).andExpect(status().isUnauthorized());
        mvc.perform(upload(subject, validFile())).andExpect(status().isUnauthorized());
        assertNoUploadSideEffects();
    }

    /** Sinh viên không được đọc/upload kể cả khi có dữ liệu phân công sai được ghi trực tiếp. */
    @Test
    void studentCannotUseMaterialEndpoints() throws Exception {
        assignments.saveAndFlush(LecturerSubjectAssignment.builder().lecturer(student).subject(subject).build());
        mvc.perform(as(get("/materials/subject/" + subject.getId()), student)).andExpect(status().isForbidden());
        mvc.perform(as(upload(subject, validFile()), student)).andExpect(status().isForbidden());
        assertNoUploadSideEffects();
    }

    /** Token cũ tự nhận LECTURER hoặc ADMIN không vượt được role hiện hành trong database. */
    @ParameterizedTest
    @ValueSource(strings = {"ROLE_LECTURER", "ROLE_ADMIN"})
    void staleAuthorityCannotReadOrUpload(String authority) throws Exception {
        mvc.perform(get("/materials/subject/" + subject.getId()).with(jwt().jwt(j -> j.subject(student.getUsername()))
                        .authorities(new SimpleGrantedAuthority(authority))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1004));
        mvc.perform(upload(subject, validFile()).with(jwt().jwt(j -> j.subject(student.getUsername()))
                        .authorities(new SimpleGrantedAuthority(authority))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1004));
        assertNoUploadSideEffects();
    }

    /** Quyền xóa/sửa tiêu đề vẫn chỉ dành cho admin, kể cả khi lecturer là chủ tài liệu và có phân công. */
    @Test
    void lecturerCannotDeleteOrRenameOwnMaterial() throws Exception {
        mvc.perform(as(delete("/materials/" + materialId), lecturer)).andExpect(status().isForbidden());
        mvc.perform(as(patch("/materials/" + materialId + "/title").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Mới\"}"), lecturer)).andExpect(status().isForbidden());
        assertThat(materials.findById(materialId).orElseThrow().getTitle()).isEqualTo("Tài liệu giữ nguyên");
    }

    /** Admin vẫn sửa tiêu đề và xóa được tài liệu như trước, không cần phân công. */
    @Test
    void adminCanRenameAndDeleteWithoutAssignment() throws Exception {
        mvc.perform(as(patch("/materials/" + materialId + "/title").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"  Tiêu đề mới  \"}"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.title").value("Tiêu đề mới"));
        mvc.perform(as(delete("/materials/" + materialId), admin)).andExpect(status().isOk());
        assertThat(materials.existsById(materialId)).isFalse();
    }

    /** Token ADMIN đã bị đổi role trong database không còn quyền sửa/xóa. */
    @Test
    void staleAdminCannotRenameOrDelete() throws Exception {
        mvc.perform(delete("/materials/" + materialId).with(jwt().jwt(j -> j.subject(lecturer.getUsername()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1004));
        mvc.perform(patch("/materials/" + materialId + "/title").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Mới\"}").with(jwt().jwt(j -> j.subject(lecturer.getUsername()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        assertThat(materials.existsById(materialId)).isTrue();
    }

    /** File rỗng và định dạng không hợp lệ vẫn giữ mã lỗi cũ khi người gọi đã có quyền. */
    @Test
    void authorizedFileValidationIsUnchanged() throws Exception {
        mvc.perform(as(upload(subject, new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0])), lecturer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1017));
        mvc.perform(as(upload(subject, new MockMultipartFile("file", "bad.exe", "application/octet-stream", new byte[]{1})), lecturer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1018));
        assertNoUploadSideEffects();
    }

    /** Môn không tồn tại tiếp tục trả mã lỗi 1016 thay vì truy vấn tài liệu hoặc gọi AI. */
    @Test
    void missingSubjectStillReturns404() throws Exception {
        mvc.perform(as(get("/materials/subject/" + Long.MAX_VALUE), lecturer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1016));
        mvc.perform(as(upload(Subject.builder().id(Long.MAX_VALUE).build(), validFile()), admin))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1016));
        assertNoUploadSideEffects();
    }

    /** Không có authentication khi gọi service trực tiếp phải trả lỗi nghiệp vụ, không NullPointerException. */
    @Test
    void directServiceCallsRequireAuthentication() {
        SecurityContextHolder.clearContext();
        expectError(() -> service.getMaterialsBySubject(subject.getId()), ErrorCode.UNAUTHENTICATED);
        expectError(() -> service.uploadAndProcessMaterial(subject.getId(), null, validFile()), ErrorCode.UNAUTHENTICATED);
        expectError(() -> service.deleteMaterial(materialId), ErrorCode.UNAUTHENTICATED);
    }

    /** Authentication ẩn danh không được coi là người dùng đã đăng nhập ở tầng service. */
    @Test
    void anonymousPrincipalIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        expectError(() -> service.getMaterialsBySubject(subject.getId()), ErrorCode.UNAUTHENTICATED);
        expectError(() -> service.uploadAndProcessMaterial(subject.getId(), null, validFile()), ErrorCode.UNAUTHENTICATED);
    }

    /** Gọi thẳng service không bỏ qua kiểm tra phân công hoặc quyền admin. */
    @Test
    void directServiceCallsCannotBypassPermissions() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(outsider.getUsername(), "unused", List.of()));
        expectError(() -> service.getMaterialsBySubject(subject.getId()), ErrorCode.SUBJECT_NOT_ASSIGNED);
        expectError(() -> service.uploadAndProcessMaterial(subject.getId(), null, validFile()), ErrorCode.SUBJECT_NOT_ASSIGNED);
        expectError(() -> service.deleteMaterial(materialId), ErrorCode.UNAUTHORIZED);
        expectError(() -> service.updateMaterialTitle(materialId, "Mới"), ErrorCode.UNAUTHORIZED);
    }

    /** Token có username đã bị xóa vẫn giữ mã USER_NOT_EXISTED của nghiệp vụ upload hiện có. */
    @Test
    void deletedAccountCannotAccessMaterials() throws Exception {
        mvc.perform(get("/materials/subject/" + subject.getId()).with(jwt().jwt(j -> j.subject("deleted"))
                .authorities(new SimpleGrantedAuthority("ROLE_LECTURER"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1006));
        mvc.perform(upload(subject, validFile()).with(jwt().jwt(j -> j.subject("deleted"))
                .authorities(new SimpleGrantedAuthority("ROLE_LECTURER"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1006));
        assertNoUploadSideEffects();
    }

    /** Thiết lập kết quả giả lập cho pipeline upload để không gọi AI thật. */
    private void prepareSuccessfulPipeline() {
        when(parser.extractText(any())).thenReturn("Nội dung");
        when(chunking.chunkText("Nội dung")).thenReturn(List.of("Nội dung"));
        when(embeddings.generateEmbeddings(List.of("Nội dung"))).thenReturn(List.of(new float[]{0.1f}));
    }

    /** Xác nhận request bị chặn chưa tạo file/metadata/chunks và chưa gọi parser hoặc AI. */
    private void assertNoUploadSideEffects() throws Exception {
        verifyNoInteractions(parser, chunking, embeddings, vectorJdbc, chunks);
        assertThat(materials.count()).isEqualTo(1);
        try (var files = Files.list(storage)) { assertThat(files.count()).isZero(); }
    }

    /** Tạo multipart request giống endpoint upload hiện tại. */
    private MockMultipartHttpServletRequestBuilder upload(Subject target, MockMultipartFile file) {
        return multipart("/materials/upload").file(file).param("subjectId", target.getId().toString());
    }

    /** File văn bản nhỏ chỉ dùng trong thư mục tạm của test. */
    private MockMultipartFile validFile() {
        return new MockMultipartFile("file", "lecture.txt", "text/plain", "Nội dung".getBytes(StandardCharsets.UTF_8));
    }

    /** Gắn principal tương ứng tài khoản fixture, không sử dụng token thật. */
    private <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, User actor) {
        return request.with(jwt().jwt(j -> j.subject(actor.getUsername()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + actor.getRole().getCode())));
    }

    /** Tạo vai trò bằng entity manager trong transaction fixture. */
    private Role role(String code) {
        Role role = Role.builder().code(code).name(code).build();
        entityManager.persist(role);
        return role;
    }

    /** Tạo tài khoản test với role xác định. */
    private User user(String username, Role role) {
        User user = User.builder().username(username).fullName(username).email(username + "@test.invalid").role(role).build();
        entityManager.persist(user);
        return user;
    }

    /** Tạo môn học phục vụ kiểm tra giới hạn theo phân công. */
    private Subject subject(String code) {
        Subject subject = Subject.builder().code(code).name(code).build();
        entityManager.persist(subject);
        return subject;
    }

    /** So sánh chính xác mã lỗi nghiệp vụ của lời gọi service trực tiếp. */
    private void expectError(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
