package com.avies.backend.exam;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.dto.request.OralExamCommandRequest;
import com.avies.backend.dto.response.*;
import com.avies.backend.entity.enums.OralExamPhase;
import com.avies.backend.exception.*;
import com.avies.backend.service.*;
import com.avies.backend.service.support.ExamAccessPolicy;
import com.sun.net.httpserver.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.sql.DataSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** Chương trình test chạy riêng: HTTP thật → controller/service thật → PostgreSQL Testcontainers, không đóng gói vào JAR production. */
public final class OralExamBrowserDemo {
    /** Token fixture chỉ decoder trong chương trình test này chấp nhận; không phải JWT production. */
    static final String TOKEN = "oral-demo-student-local-only";

    /** Khởi tạo database mới do container sở hữu, không đọc application.yaml/.env hoặc URL Supabase. */
    public static void main(String[] args) throws Exception {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(DemoConfig.class); context.refresh();
        var source = context.getBean(DataSource.class);
        var postgres = context.getBean(PostgreSQLContainer.class);
        try (Connection connection = source.getConnection()) {
            // Chỉ seed nếu datasource đúng container vừa được context này tạo.
            if (!connection.getMetaData().getURL().equals(postgres.getJdbcUrl())) throw new IllegalStateException("Demo chỉ chạy với container riêng.");
            ExamFoundationPostgresConfig.execute(connection, ExamFoundationPostgresConfig.projectSql("docs/migrations/20261025_feature2_exam_lifecycle.sql"));
            String baseline = ExamFoundationPostgresConfig.projectSql("docs/database/AIVES_DB_POSTGRESQL_FINAL.sql");
            int start = baseline.indexOf("CREATE TABLE IF NOT EXISTS transcripts ("); int end = baseline.indexOf("\n);", start) + 3;
            ExamFoundationPostgresConfig.execute(connection, "SET search_path=aives,public;" + baseline.substring(start, end));
            ExamFoundationPostgresConfig.execute(connection, ExamFoundationPostgresConfig.projectSql("docs/migrations/20261028_feature3_oral_progress.sql"));
        }
        var pipeline = context.getBean(DemoPipeline.class); pipeline.seed();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        int port = Integer.parseInt(System.getProperty("oral.demo.port", "8081"));
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Cổng demo không hợp lệ.");
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        var httpPool = Executors.newFixedThreadPool(4);
        server.setExecutor(httpPool);
        server.createContext("/", exchange -> forward(mvc, exchange));
        server.start(); pipeline.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); pipeline.close(); httpPool.shutdownNow(); context.close(); }, "oral-demo-shutdown"));
        System.out.println("ORAL DEMO READY http://127.0.0.1:" + port + " | Đăng nhập student / oral-demo | PostgreSQL tạm, không dùng Supabase hoặc AI thật.");
        new CountDownLatch(1).await();
    }

    /** Bridge HTTP loopback sang MockMvc để dùng nguyên controller, validation, JWT và transaction thật. */
    private static void forward(MockMvc mvc, HttpExchange exchange) {
        try (exchange) {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null) {
                URI uri = URI.create(origin);
                if (!"http".equals(uri.getScheme()) || !Set.of("localhost", "127.0.0.1").contains(uri.getHost())) { exchange.sendResponseHeaders(403, -1); return; }
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                exchange.getResponseHeaders().set("Vary", "Origin");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization,Content-Type,Accept");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            }
            if (exchange.getRequestMethod().equals("OPTIONS")) { exchange.sendResponseHeaders(204, -1); return; }
            byte[] body = exchange.getRequestBody().readNBytes(65537);
            if (body.length > 65536) { exchange.sendResponseHeaders(413, -1); return; }
            var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(exchange.getRequestMethod()), exchange.getRequestURI())
                    .content(body).characterEncoding(StandardCharsets.UTF_8);
            for (String header : List.of("Authorization", "Content-Type", "Accept")) {
                String value = exchange.getRequestHeaders().getFirst(header); if (value != null) request.header(header, value);
            }
            var response = mvc.perform(request).andReturn().getResponse();
            if (response.getContentType() != null) exchange.getResponseHeaders().set("Content-Type", response.getContentType());
            byte[] bytes = response.getContentAsByteArray();
            exchange.sendResponseHeaders(response.getStatus(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        } catch (Exception exception) {
            // Không ghi body/token và không gọi dịch vụ bên ngoài khi bridge lỗi.
            System.err.println("Không thể xử lý request demo: " + exception.getClass().getSimpleName());
        }
    }

    /** Chỉ cấu hình test được main nạp, không component-scan production hoặc đọc secrets. */
    @Configuration
    @Import({OralExamPostgresConfig.class, DemoController.class, DemoAdvice.class})
    public static class DemoConfig {
        /** Decoder whitelist token fixture; không chấp nhận JWT thật gửi nhầm vào demo. */
        @Bean static BeanFactoryPostProcessor demoDecoder() {
            // Singleton test không chạy autowire key/auth service của decoder production.
            return factory -> {
                // Không autowire @Value của TTS: kể cả terminal có key thật, demo vẫn không gọi Google.
                factory.registerSingleton("oralSpeechProperties", new com.avies.backend.config.OralSpeechProperties());
                factory.registerSingleton("oralTranscriptionProperties", new com.avies.backend.config.OralTranscriptionProperties());
                factory.registerSingleton("oralDecisionProperties", new com.avies.backend.config.OralDecisionProperties());
                factory.registerSingleton("customJwtDecoder", new CustomJwtDecoder() {
                    @Override public Jwt decode(String token) {
                        if (!TOKEN.equals(token)) throw new BadJwtException("Chỉ dùng token demo.");
                        return Jwt.withTokenValue(TOKEN).header("alg", "TEST_ONLY").subject("student").claim("scope", "ROLE_STUDENT")
                                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
                    }
                });
            };
        }
        /** Pipeline mẫu chỉ sống trong src/test và sử dụng datasource container đã được kiểm chứng. */
        @Bean DemoPipeline demoPipeline(DataSource source, OralExamService oral) { return new DemoPipeline(new JdbcTemplate(source), oral); }
        /** Khả năng demo được khai báo bởi backend này, không dựa cờ frontend. */
        @Bean @Primary OralExamRuntimeService demoRuntime(ExamAccessPolicy access) {
            return () -> {
                access.currentUser("STUDENT");
                return new OralExamRuntimeResponse("LOCAL_DEMO", true, "Kiểm thử tích hợp với PostgreSQL tạm. Giọng đọc, transcript và follow-up dùng dịch vụ mẫu; không ghi Supabase.");
            };
        }
    }

    /** HTTP phụ trợ chỉ nằm trong chương trình test, không có trong backend production. */
    @RestController
    public static class DemoController {
        /** Controller chỉ gọi dịch vụ demo đã được cô lập. */ private final DemoPipeline service;
        /** Khởi tạo bằng DI trong context test. */ public DemoController(DemoPipeline service) { this.service = service; }
        /** Tài khoản fixture rõ ràng, không đọc tài khoản hoặc mật khẩu thật. */
        @PostMapping("/auth/signin")
        public ApiResponse<AuthenticationResponse> login(@Valid @RequestBody DemoLogin request) {
            return ApiResponse.<AuthenticationResponse>builder().result(service.login(request)).build();
        }
        /** Chỉ chọn tình huống cho lượt hiện tại của chính Student trong DB tạm. */
        @PostMapping("/_oral-demo/student-exams/{id}/scenario")
        @PreAuthorize("hasAuthority('ROLE_STUDENT')")
        public ApiResponse<Void> scenario(@PathVariable Long id, @Valid @RequestBody DemoChoice request) {
            service.choose(id, request); return ApiResponse.<Void>builder().build();
        }
    }
    /** Credentials fixture, không phải tài khoản Supabase. */
    public record DemoLogin(@NotBlank String username, @NotBlank String password) { }
    /** Công cụ thử lỗi chỉ có trong test server cục bộ. */
    public record DemoChoice(@NotNull UUID turnId, @NotNull Scenario scenario) { }
    /** Các nhánh mẫu phục vụ kiểm thử, không phải quyết định do AI thật đưa ra. */
    public enum Scenario { CLEAR, BRIEF, EMPTY, TECHNICAL_ERROR }

    /** Lỗi tài khoản/tình huống test trả envelope chuẩn, không đóng kết nối hoặc lộ stacktrace. */
    @RestControllerAdvice(assignableTypes = DemoController.class)
    public static class DemoAdvice {
        /** Chỉ trả mã nghiệp vụ kiểm soát. */
        @ExceptionHandler(AppException.class)
        public ResponseEntity<ApiResponse<Void>> business(AppException failure) {
            var error = failure.getErrorCode();
            return ResponseEntity.status(error.getHttpStatusCode()).body(ApiResponse.<Void>builder().code(error.getCode()).message(error.getMessage()).build());
        }
    }

    /** Dịch vụ mẫu chạy nền gọi đúng các callback nội bộ production. */
    public static class DemoPipeline implements AutoCloseable {
        /** JDBC chỉ do container test cung cấp. */ private final JdbcTemplate jdbc;
        /** Bộ điều phối production. */ private final OralExamService oral;
        /** Kịch bản theo turn, không nhận transcript tự khai. */ private final Map<UUID,Scenario> choices = new ConcurrentHashMap<>();
        /** Giữ lựa chọn khi retry cùng submission. */ private final Map<UUID,Scenario> jobs = new ConcurrentHashMap<>();
        /** Ngôn ngữ được khai báo trong fixture, không đoán hoặc dịch câu ngân hàng. */ private final Map<Long,String> languages = new ConcurrentHashMap<>();
        /** Một worker duy nhất, không có truy cập mạng AI. */ private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();

        /** Dùng DI test, không nhận JDBC URL từ tham số hoặc môi trường. */
        DemoPipeline(JdbcTemplate jdbc, OralExamService oral) { this.jdbc = jdbc; this.oral = oral; }
        /** Seed chỉ được main gọi sau khi đối chiếu datasource với container vừa tạo. */
        void seed() {
            var fixture = new ExamQuestionSelectionFixture(jdbc); fixture.reset();
            jdbc.update("UPDATE aives.users SET full_name='Sinh viên thử tích hợp' WHERE username='student'");
            long vi = fixture.question(); long en = fixture.question(); long vi2 = fixture.question();
            jdbc.update("UPDATE aives.questions SET content='Hãy giải thích mục đích của Use Case.',answer_time_limit_seconds=90 WHERE id=?", vi);
            jdbc.update("UPDATE aives.questions SET content='Explain the difference between functional and non-functional requirements.',answer_time_limit_seconds=90 WHERE id=?", en);
            jdbc.update("UPDATE aives.questions SET content='Vì sao cần xác nhận yêu cầu với các bên liên quan?',answer_time_limit_seconds=90 WHERE id=?", vi2);
            languages.put(vi, "vi"); languages.put(en, "en"); languages.put(vi2, "vi");
            OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5).withNano(0);
            for (int i = 1; i <= 3; i++) {
                long session = fixture.session(3); long exam = fixture.exam(session, 4, 1);
                jdbc.update("UPDATE aives.exam_sessions SET title=?,scheduled_start_at=?,scheduled_end_at=?,slot_duration_minutes=12 WHERE id=?", "Demo liên thông FE–BE " + i, start, start.plusMinutes(36), session);
                jdbc.update("UPDATE aives.student_exams SET scheduled_start_at=?,scheduled_end_at=? WHERE id=?", start, start.plusMinutes(12), exam);
            }
            long shortQuestion = fixture.question(2, "APPROVED", "UNDERSTAND"); languages.put(shortQuestion, "vi");
            jdbc.update("UPDATE aives.questions SET content='Hãy nêu mục đích của việc kiểm thử phần mềm.',answer_time_limit_seconds=10 WHERE id=?", shortQuestion);
            long session = fixture.base.session(1, 2, "UPCOMING"); long exam = fixture.exam(session, 4, 1);
            jdbc.update("UPDATE aives.exam_sessions SET title='Demo hết giờ câu — 10 giây',max_main_questions=1,max_follow_ups=0,scheduled_start_at=?,scheduled_end_at=?,slot_duration_minutes=5 WHERE id=?", start, start.plusMinutes(15), session);
            jdbc.update("UPDATE aives.student_exams SET scheduled_start_at=?,scheduled_end_at=? WHERE id=?", start, start.plusMinutes(5), exam);
        }
        /** Xác nhận đúng tài khoản test, không lưu hoặc ghi log credentials. */
        AuthenticationResponse login(DemoLogin request) {
            if (!"student".equals(request.username()) || !"oral-demo".equals(request.password())) throw new AppException(ErrorCode.INVALID_CREDENTIALS);
            // Chỉ làm mới ca fixture CHƯA bắt đầu; không gia hạn ca đã IN_PROGRESS/đã hoàn thành.
            OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5).withNano(0);
            var unused = jdbc.queryForList("SELECT s.id,s.slot_duration_minutes FROM aives.exam_sessions s JOIN aives.student_exams e ON e.session_id=s.id WHERE s.status='UPCOMING' AND e.status='ASSIGNED' AND e.started_at IS NULL");
            for (var row : unused) {
                long id = ((Number) row.get("id")).longValue(); long minutes = ((Number) row.get("slot_duration_minutes")).longValue();
                jdbc.update("UPDATE aives.exam_sessions SET scheduled_start_at=?,scheduled_end_at=? WHERE id=?", start, start.plusMinutes(minutes * 3), id);
                jdbc.update("UPDATE aives.student_exams SET scheduled_start_at=?,scheduled_end_at=? WHERE session_id=? AND status='ASSIGNED'", start, start.plusMinutes(minutes), id);
            }
            return AuthenticationResponse.builder().token(TOKEN).authenticated(true).role("STUDENT").username("student").fullName("Sinh viên thử tích hợp").build();
        }
        /** Chọn nhánh mẫu chỉ khi đúng turn đang ANSWERING và đúng chủ lượt. */
        void choose(Long examId, DemoChoice choice) {
            var state = oral.get(examId);
            if (state.phase() != OralExamPhase.ANSWERING || !choice.turnId().equals(state.currentTurnId())) throw new AppException(ErrorCode.ORAL_STALE_COMMAND);
            choices.put(choice.turnId(), choice.scenario());
        }
        /** Worker xử lý công việc pending thật trong PostgreSQL, không dùng bộ đếm ở frontend. */
        void start() { worker.scheduleWithFixedDelay(this::tick, 700, 700, TimeUnit.MILLISECONDS); }
        /** Callback có thể gặp lệnh đồng thời; BE quyết định trạng thái mới và tính idempotent. */
        void tick() {
            var candidates = jdbc.queryForList("SELECT student_exam_id FROM aives.oral_exam_progress WHERE phase IN ('PREPARING','TRANSCRIBING','DECIDING') ORDER BY student_exam_id", Long.class);
            for (long examId : candidates) {
                try {
                    var row = jdbc.queryForMap("SELECT p.phase,p.current_turn_id,t.language,t.student_exam_question_id,q.question_id FROM aives.oral_exam_progress p JOIN aives.oral_exam_turns t ON t.id=p.current_turn_id JOIN aives.student_exam_questions q ON q.id=t.student_exam_question_id WHERE p.student_exam_id=?", examId);
                    UUID turnId = (UUID) row.get("current_turn_id");
                    String language = row.get("language") == null ? languages.get(((Number) row.get("question_id")).longValue()) : row.get("language").toString();
                    String phase = row.get("phase").toString();
                    if (phase.equals("PREPARING")) { oral.promptReady(examId, turnId, language); continue; }
                    var submission = jdbc.queryForList("SELECT id FROM aives.oral_answer_submissions WHERE turn_id=? AND status IN ('PROCESSING','TRANSCRIBED')", UUID.class, turnId);
                    if (submission.isEmpty()) continue;
                    UUID job = submission.getFirst();
                    if (phase.equals("TRANSCRIBING")) {
                        Scenario scenario = jobs.computeIfAbsent(job, ignored -> Optional.ofNullable(choices.remove(turnId)).orElse(Scenario.BRIEF));
                        if (scenario == Scenario.TECHNICAL_ERROR) { oral.technicalFailure(examId, job); jobs.put(job, Scenario.BRIEF); }
                        else {
                            String text = scenario == Scenario.EMPTY ? "" : "en".equals(language)
                                    ? "Sample transcript for the local integration test. This is not speech recognition."
                                    : "Transcript mẫu phục vụ kiểm thử liên thông cục bộ; chưa nhận dạng lời nói thật.";
                            oral.transcriptReady(examId, job, text);
                        }
                    } else if (phase.equals("DECIDING")) {
                        boolean followUp = jobs.getOrDefault(job, Scenario.BRIEF) == Scenario.BRIEF;
                        oral.decide(examId, job, followUp, followUp ? ("en".equals(language) ? "Could you give an example?" : "Em có thể nêu một ví dụ không?") : null);
                        jobs.remove(job);
                    }
                } catch (AppException changed) { /* Lệnh đồng thời/timeout được BE xử lý, tick sau đọc lại trạng thái. */ }
                catch (Exception error) { System.err.println("Worker demo chưa hoàn tất: " + error.getClass().getSimpleName()); }
            }
        }
        /** Dọn luồng và database khi main kết thúc. */
        @Override public void close() { worker.shutdownNow(); }
    }
}
