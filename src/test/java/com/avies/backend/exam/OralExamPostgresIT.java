package com.avies.backend.exam;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.OralExamResponse;
import com.avies.backend.entity.enums.*;
import com.avies.backend.exception.AppException;
import com.avies.backend.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.avies.backend.exam.ExamSessionFixture.as;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP/JPA/JDBC/PostgreSQL thật trên container tạm; callback chỉ dùng dữ liệu fixture, không gọi AI. */
@SpringJUnitConfig(OralExamPostgresConfig.class)
@WebAppConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.context.event.RecordApplicationEvents
class OralExamPostgresIT {
    /** Chỉ quan sát tín hiệu nội bộ trong test, không khởi động worker hoặc provider thật. */
    @Autowired private org.springframework.test.context.event.ApplicationEvents events;
    /** JWT giả trong test HTTP, quyền DB vẫn kiểm tra thật. */ @MockitoBean private CustomJwtDecoder decoder;
    /** Không đợi thời gian thực hoặc chỉnh đồng hồ hệ điều hành. */ @MockitoBean private Clock clock;
    /** Không gọi mạng AI khi kiểm thử HTTP/DB. */ @MockitoBean private OralSpeechProvider speechProvider;
    /** Không đọc key TTS từ môi trường máy developer. */ @MockitoBean private com.avies.backend.config.OralSpeechProperties speechSettings;
    /** Không bao giờ gọi Groq thật trong test DB. */ @MockitoBean private OralTranscriptionProvider sttProvider;
    /** Thay đường dẫn spool bằng thư mục test. */ @MockitoBean private com.avies.backend.config.OralTranscriptionProperties sttSettings;
    /** File test không được ghi vào private spool thật. */ @org.junit.jupiter.api.io.TempDir java.nio.file.Path audioDirectory;
    /** Kiểm tra metadata phục hồi job. */ @Autowired private com.avies.backend.repository.OralAudioRepository audioRepository;
    /** Store thật dùng temp directory do test cung cấp. */ @Autowired private com.avies.backend.service.support.OralAudioStore audioStore;
    /** Không đọc key/cờ AI thật từ máy developer. */ @MockitoBean private com.avies.backend.config.OralDecisionProperties decisionSettings;
    /** Quan sát claim AI riêng. */ @Autowired private com.avies.backend.repository.OralDecisionRepository decisionRepository;
    /** Context web có method security và exception handler thật. */ @Autowired private WebApplicationContext context;
    /** PostgreSQL container do test sở hữu. */ @Autowired private DataSource source;
    /** Điều phối thật, không mock service. */ @Autowired private OralExamService service;
    /** Kiểm tra Finish/Close không bị luồng mới vượt qua. */ @Autowired private ExamLifecycleService lifecycle;
    /** Giữ transaction để kiểm tra khóa đồng thời. */ @Autowired private PlatformTransactionManager manager;
    /** SQL quan sát sau commit. */ private JdbcTemplate jdbc;
    /** Fixture cô lập. */ private ExamQuestionSelectionFixture fixture;
    /** Client HTTP trong bộ nhớ. */ private MockMvc mvc;
    /** Phiên thi và lượt thi mới cho từng test. */ private long parent, exam;
    /** Tab mặc định trong test. */ private UUID client;
    /** Response cuối cùng để tạo lệnh đúng phiên bản. */ private OralExamResponse state;

    /** Chạy migration nguyên bản hai lần để kiểm tra khả năng retry và không tác động dữ liệu ngoài. */
    @BeforeAll void schema() throws Exception {
        try (Connection connection = source.getConnection()) {
            ExamFoundationPostgresConfig.execute(connection, ExamFoundationPostgresConfig.projectSql("docs/migrations/20261025_feature2_exam_lifecycle.sql"));
            String baseline = ExamFoundationPostgresConfig.projectSql("docs/database/AIVES_DB_POSTGRESQL_FINAL.sql");
            int first = baseline.indexOf("CREATE TABLE IF NOT EXISTS transcripts ("); int last = baseline.indexOf("\n);", first) + 3;
            ExamFoundationPostgresConfig.execute(connection, "SET search_path=aives,public;" + baseline.substring(first, last));
            String migration = ExamFoundationPostgresConfig.projectSql("docs/migrations/20261028_feature3_oral_progress.sql");
            ExamFoundationPostgresConfig.execute(connection, migration); ExamFoundationPostgresConfig.execute(connection, migration);
            String audio = ExamFoundationPostgresConfig.projectSql("docs/migrations/20261029_feature3_audio_submissions.sql");
            ExamFoundationPostgresConfig.execute(connection, audio); ExamFoundationPostgresConfig.execute(connection, audio);
            String decision=ExamFoundationPostgresConfig.projectSql("docs/migrations/20261029_feature3_ai_decisions.sql");
            ExamFoundationPostgresConfig.execute(connection,decision); ExamFoundationPostgresConfig.execute(connection,decision);
        }
    }
    /** Lịch thật trong container và mốc cố định tại đầu slot, không cần quyền truy cập Supabase. */
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(source); fixture = new ExamQuestionSelectionFixture(jdbc); fixture.reset();
        parent = fixture.session(2); exam = fixture.exam(parent, 4, 1); fixture.question(); fixture.question();
        jdbc.update("UPDATE aives.questions SET content='PRIVATE_MAIN_PROMPT',answer_time_limit_seconds=90");
        when(clock.getZone()).thenReturn(ZoneOffset.UTC); at(fixture.base.start);
        when(sttSettings.getDirectory()).thenReturn(audioDirectory.toString());
        client = UUID.randomUUID(); state = null; student();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    /** Không giữ danh tính test ngoài phạm vi từng ca. */
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    /** Poll không khóa parent hoặc UPDATE aggregate; chỉ nội suy remaining trên snapshot đọc. */
    @Test void pollingDoesNotBlockWorkerParentsOrRewriteProgress() throws Exception {
        open(); listen();
        String before=jdbc.queryForObject("SELECT xmin::text FROM aives.oral_exam_progress WHERE student_exam_id=?",String.class,exam);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var pool=Executors.newSingleThreadExecutor()) {
            var holder=pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM aives.exam_sessions WHERE id=? FOR UPDATE",Long.class,parent); locked.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timeout"); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue(); at(fixture.base.start.plusSeconds(12));
                student(); state=service.get(exam); assertThat(state.answerRemainingMs()).isEqualTo(78000L);
                assertThat(jdbc.queryForObject("SELECT xmin::text FROM aives.oral_exam_progress WHERE student_exam_id=?",String.class,exam)).isEqualTo(before);
            } finally { release.countDown(); holder.get(10,TimeUnit.SECONDS); }
        }
        command(OralExamAction.SUBMIT); assertThat(state.answerRemainingMs()).isEqualTo(78000L);
    }
    /** Heartbeat riêng không dùng revision cũ, không tạo receipt/SUBMIT hoặc gia hạn slot. */
    @Test void dedicatedLeaseRenewsWithoutBusinessWritesAndRejectsOtherTab() throws Exception {
        open(); long revision=state.revision(); var deadline=state.deadline(); var turn=state.currentTurnId();
        at(fixture.base.start.plusSeconds(40));
        mvc.perform(as(post(url()+"/lease").contentType(MediaType.APPLICATION_JSON).content(startBody()),"student"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.leaseUntil").isNotEmpty());
        student(); state=service.get(exam); assertThat(state.leaseUntil()).isEqualTo(fixture.base.start.plusSeconds(85));
        assertThat(state.revision()).isEqualTo(revision); assertThat(state.deadline()).isEqualTo(deadline); assertThat(state.currentTurnId()).isEqualTo(turn);
        assertThat(count("oral_exam_commands")).isZero();
        mvc.perform(as(post(url()+"/lease").contentType(MediaType.APPLICATION_JSON).content("{\"clientSessionId\":\""+UUID.randomUUID()+"\"}"),"student"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1803));
    }
    /** Heartbeat đến sau Finish không bị hiểu nhầm thành tab khác và không ghi lại kết quả. */
    @Test void heartbeatAfterCompletionReturnsTerminalReceipt() throws Exception {
        jdbc.update("UPDATE aives.exam_sessions SET max_main_questions=1 WHERE id=?",parent);
        open(); listen(); answer(false); assertThat(state.phase()).isEqualTo(OralExamPhase.COMPLETED);
        var before=jdbc.queryForObject("SELECT xmin::text FROM aives.oral_exam_progress WHERE student_exam_id=?",String.class,exam);
        mvc.perform(as(post(url()+"/lease").contentType(MediaType.APPLICATION_JSON).content(startBody()),"student"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.terminal").value(true)).andExpect(jsonPath("$.result.leaseUntil").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT xmin::text FROM aives.oral_exam_progress WHERE student_exam_id=?",String.class,exam)).isEqualTo(before);
    }
    /** Deadline đã qua cũng cần đọc trạng thái cuối thay vì yêu cầu nhận lại quyền tab. */
    @Test void heartbeatAtDeadlineReturnsTerminalReceipt() {
        open(); at(state.deadline());
        var receipt=service.renewLease(exam,new OralExamStartRequest(client)); assertThat(receipt.terminal()).isTrue(); assertThat(receipt.leaseUntil()).isNull();
        state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.EXPIRED);
    }

    /** Service phát đúng tín hiệu STT → AI → audio của turn mới, không làm nóng toàn bộ đề. */
    @Test void committedPipelinePublishesOnlyCurrentWorkAndPrompt() {
        open(); listen(); command(OralExamAction.SUBMIT); var job=state.submissionId(); attachFixture(job);
        var transcription=service.claimTranscription(exam,job); service.completeTranscription(transcription,"Câu trả lời đã lưu",null);
        var warm=events.stream(com.avies.backend.service.support.OralSpeechWarmup.class).toList();
        assertThat(warm).hasSize(1); assertThat(warm.getFirst().plan().text()).isEqualTo("Question number 1. PRIVATE_MAIN_PROMPT");
        assertThat(warm.getFirst().deadline()).isEqualTo(state.deadline());
        var jobs=events.stream(com.avies.backend.service.support.OralWorkReady.class).toList();
        assertThat(jobs).hasSize(2); assertThat(jobs).allMatch(event -> event.examId()==exam && event.submissionId().equals(job));
        assertThat(jobs).extracting(com.avies.backend.service.support.OralWorkReady::stage)
                .containsExactly(com.avies.backend.service.support.OralWorkReady.Stage.STT,com.avies.backend.service.support.OralWorkReady.Stage.DECISION);
        var work=service.claimDecision(exam,job); service.completeDecision(work,com.avies.backend.service.support.OralDecisionResult.advance(),null);
        warm=events.stream(com.avies.backend.service.support.OralSpeechWarmup.class).toList();
        assertThat(warm).hasSize(2); assertThat(warm.getLast().plan().text()).isEqualTo("Question number 2. PRIVATE_MAIN_PROMPT");
        assertThat(warm.getLast().plan().turnId()).isNotEqualTo(warm.getFirst().plan().turnId());
    }

    /** STT không được treo mười phút; timeout kỹ thuật vô hiệu claim cũ và giữ nguyên audio để retry. */
    @Test void sttWatchdogReleasesOldClaimAndAllowsRetry() {
        open(); listen(); command(OralExamAction.SUBMIT); var job=state.submissionId(); attachFixture(job); var old=service.claimTranscription(exam,job);
        at(fixture.base.start.plusSeconds(91)); student(); state=service.get(exam);
        assertThat(state.phase()).isEqualTo(OralExamPhase.TECHNICAL_ERROR); assertThat(state.processingErrorCode()).isEqualTo(1811); assertThat(state.audioReceived()).isTrue();
        assertThat(state.answerRemainingMs()).isEqualTo(90000L); assertThat(state.deadline()).isEqualTo(fixture.base.start.plusMinutes(20));
        service.renewLease(exam,new OralExamStartRequest(client));
        command(OralExamAction.RETRY); var fresh=service.claimTranscription(exam,job); assertThat(fresh).isNotNull();
        service.completeTranscription(old,"OLD_RESULT",null); assertThat(answerCount()).isZero();
        service.completeTranscription(fresh,"Bản ghi được phục hồi",null); student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.DECIDING);
    }
    /** Timeout AI không xóa transcript, không tạo NO_ANSWER và RETRY có mốc chờ mới. */
    @Test void decisionWatchdogKeepsTranscriptAndRetriesWithoutStt() {
        var job=transcribedFixture(); var old=service.claimDecision(exam,job); at(fixture.base.start.plusSeconds(91));
        student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.TECHNICAL_ERROR); assertThat(state.processingErrorCode()).isEqualTo(1816); assertThat(answerCount()).isEqualTo(1);
        service.renewLease(exam,new OralExamStartRequest(client));
        command(OralExamAction.RETRY); var fresh=service.claimDecision(exam,job); assertThat(fresh).isNotNull();
        service.completeDecision(old,null,1816); service.completeDecision(fresh,com.avies.backend.service.support.OralDecisionResult.advance(),null);
        student(); state=service.get(exam); assertThat(state.currentQuestionOrder()).isEqualTo(2); verifyNoInteractions(sttProvider);
    }

    /** Context chỉ đọc snapshot cùng transcript đã commit; nonce lặp/khác không đổi bằng chứng. */
    @Test void decisionContextAndCommitAreSnapshotBasedAndIdempotent() {
        var job=transcribedFixture(); jdbc.update("UPDATE aives.questions SET content='CHANGED_BANK',reference_answer='PRIVATE_NEW_ANSWER'");
        var work=service.claimDecision(exam,job); assertThat(work.mainPrompt()).isEqualTo("PRIVATE_MAIN_PROMPT");
        assertThat(work.history().getFirst().answer()).isEqualTo("Câu trả lời STT thật fixture"); assertThat(service.claimDecision(exam,job)).isNull();
        var wrong=new com.avies.backend.service.support.OralDecisionWork(exam,job,UUID.randomUUID(),work.language(),work.mainPrompt(),work.history(),true);
        service.completeDecision(wrong,com.avies.backend.service.support.OralDecisionResult.advance(),null);
        student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.DECIDING);
        service.completeDecision(work,new com.avies.backend.service.support.OralDecisionResult(true,"Bạn có thể giải thích rõ lập luận vừa nêu không?"),null);
        service.completeDecision(work,com.avies.backend.service.support.OralDecisionResult.advance(),null);
        student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.PREPARING); assertThat(state.followUpsUsed()).isEqualTo(1);
        assertThat(count("oral_exam_turns")).isEqualTo(2); assertThat(answerCount()).isEqualTo(1); assertThat(decisionRepository.candidates()).isEmpty();
    }
    /** RETRY AI dùng lại transcript/job, không gọi STT hoặc cấp thêm ngân sách câu. */
    @Test void failedDecisionRetriesSameTranscriptWithoutAnotherAnswer() {
        var job=transcribedFixture(); var work=service.claimDecision(exam,job); service.completeDecision(work,null,1817);
        student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.TECHNICAL_ERROR); assertThat(state.processingErrorCode()).isEqualTo(1817);
        assertThat(answerCount()).isEqualTo(1); command(OralExamAction.RETRY); var retry=service.claimDecision(exam,job);
        assertThat(retry.claimId()).isNotEqualTo(work.claimId()); assertThat(retry.history()).isEqualTo(work.history());
        service.completeDecision(retry,com.avies.backend.service.support.OralDecisionResult.advance(),null);
        student(); state=service.get(exam); assertThat(state.currentQuestionOrder()).isEqualTo(2); assertThat(answerCount()).isEqualTo(1); verifyNoInteractions(sttProvider);
    }
    /** Claim quá hạn sau crash được phục hồi; callback cũ không thắng worker mới. */
    @Test void decisionClaimCanRecoverAfterRestartWithoutOldCallbackWinning() {
        var job=transcribedFixture(); var old=service.claimDecision(exam,job); at(fixture.base.start.plusSeconds(61));
        var fresh=service.claimDecision(exam,job); assertThat(fresh).isNotNull(); assertThat(fresh.claimId()).isNotEqualTo(old.claimId());
        service.completeDecision(old,null,1816); student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.DECIDING);
        service.completeDecision(fresh,com.avies.backend.service.support.OralDecisionResult.advance(),null);
        student(); state=service.get(exam); assertThat(state.currentQuestionOrder()).isEqualTo(2); assertThat(state.followUpsUsed()).isZero();
    }
    /** Kết quả AI về sau deadline không sinh câu mới hoặc biến EXPIRED thành COMPLETED. */
    @Test void lateDecisionCannotReviveAttempt() {
        var job=transcribedFixture(); var work=service.claimDecision(exam,job); at(fixture.base.start.plusMinutes(21));
        service.completeDecision(work,new com.avies.backend.service.support.OralDecisionResult(true,"Một câu hỏi đến quá muộn?"),null);
        student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.EXPIRED); assertThat(count("oral_exam_turns")).isEqualTo(1); assertThat(answerCount()).isEqualTo(1);
    }
    /** Bộ giới hạn BE vẫn áp dụng kể cả provider cố trả follow-up sau khi đã hết quota. */
    @Test void backendEnforcesGlobalFollowUpQuota() {
        jdbc.update("UPDATE aives.exam_sessions SET max_follow_ups=1 WHERE id=?",parent);
        var first=transcribedFixture(); var work=service.claimDecision(exam,first);
        service.completeDecision(work,new com.avies.backend.service.support.OralDecisionResult(true,"Vì sao bạn chọn cách lập luận đó?"),null);
        student(); state=service.get(exam); listen(); command(OralExamAction.SUBMIT); var second=state.submissionId(); providerTranscript(second,"Em giải thích thêm bằng lời.");
        var next=service.claimDecision(exam,second); assertThat(next.canFollowUp()).isFalse();
        service.completeDecision(next,new com.avies.backend.service.support.OralDecisionResult(true,"Câu hỏi vượt quota?"),null);
        student(); state=service.get(exam); assertThat(state.followUpsUsed()).isEqualTo(1); assertThat(state.currentQuestionOrder()).isEqualTo(2); assertThat(count("oral_exam_turns")).isEqualTo(3);
    }
    /** Pipeline worker thật chạy đến COMPLETED mà không gọi mạng hoặc giữ transaction ở provider. */
    @Test void decisionWorkerCompletesAllMainQuestionsAndCallsProviderOutsideTransaction() {
        var provider=mock(OralDecisionProvider.class);
        when(provider.decide(any())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return com.avies.backend.service.support.OralDecisionResult.advance();
        });
        var worker=new com.avies.backend.service.support.OralDecisionWorker(decisionSettings,decisionRepository,service,provider,null);
        var first=transcribedFixture(); worker.process(exam,first); worker.process(exam,first);
        student(); state=service.get(exam); listen(); command(OralExamAction.SUBMIT); var second=state.submissionId(); providerTranscript(second,"Câu trả lời thứ hai."); worker.process(exam,second);
        student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.COMPLETED); assertThat(answerCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM aives.student_exams WHERE id=?",Boolean.class,exam)).isTrue();
        verify(provider,times(2)).decide(any());
    }
    /** HTTP và worker liên thông: phát câu chính/follow-up, nhận audio, lưu transcript và Finish. */
    @Test void httpAudioPipelineRunsMainFollowUpAndNextMainToCompletion() throws Exception {
        jdbc.update("UPDATE aives.questions SET content='PRIVATE_MAIN_PROMPT Vì sao cần giải thích lập luận?'");
        when(speechSettings.ready()).thenReturn(true); when(sttSettings.ready()).thenReturn(true);
        when(speechProvider.synthesize(anyString(),eq("vi"))).thenReturn(com.avies.backend.service.impl.GeminiOralSpeechProviderTest.wav());
        when(sttProvider.transcribe(any(),eq("vi"))).thenReturn("Bản trả lời nhận dạng từ audio fixture.");
        var decisions=mock(OralDecisionProvider.class);
        when(decisions.decide(any())).thenReturn(new com.avies.backend.service.support.OralDecisionResult(true,"Vì sao bạn chọn lập luận đó?"),
                com.avies.backend.service.support.OralDecisionResult.advance(),com.avies.backend.service.support.OralDecisionResult.advance());
        var stt=new com.avies.backend.service.support.OralTranscriptionWorker(sttSettings,audioRepository,service,audioStore,sttProvider,null);
        var ai=new com.avies.backend.service.support.OralDecisionWorker(decisionSettings,decisionRepository,service,decisions,null);
        mvc.perform(as(post(url()).contentType(MediaType.APPLICATION_JSON).content(startBody()),"student")).andExpect(status().isOk());
        for (int turn=0; turn<3; turn++) {
            student(); state=service.get(exam);
            String body="{\"clientSessionId\":\""+client+"\",\"turnId\":\""+state.currentTurnId()+"\",\"expectedRevision\":"+state.revision()+"}";
            mvc.perform(as(post(url()+"/speech").contentType(MediaType.APPLICATION_JSON).content(body),"student"))
                    .andExpect(status().isOk()).andExpect(content().contentType("audio/wav"));
            student(); state=service.get(exam); command(OralExamAction.PROMPT_FINISHED); command(OralExamAction.SUBMIT); UUID job=state.submissionId();
            mvc.perform(as(multipart(url()+"/submissions/"+job+"/audio")
                    .file(new org.springframework.mock.web.MockMultipartFile("metadata","metadata.json","application/json",audioMetadata().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .file(new org.springframework.mock.web.MockMultipartFile("audio","answer.wav","audio/wav",com.avies.backend.service.impl.GroqOralTranscriptionProviderTest.audio())),"student"))
                    .andExpect(status().isAccepted());
            SecurityContextHolder.clearContext(); stt.process(exam,job); ai.process(exam,job);
        }
        mvc.perform(as(get(url()),"student")).andExpect(status().isOk()).andExpect(jsonPath("$.result.phase").value("COMPLETED"))
                .andExpect(jsonPath("$.result.followUpsUsed").value(1)).andExpect(jsonPath("$.result.transcripts.length()").value(3))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_MAIN_PROMPT"))));
        verify(speechProvider).synthesize(startsWith("Câu số 1, câu hỏi đào sâu 1."),eq("vi"));
        verify(speechProvider).synthesize(startsWith("Câu số 2."),eq("vi"));
        assertThat(answerCount()).isEqualTo(3); verify(sttProvider,times(3)).transcribe(any(),eq("vi"));
    }

    /** Hai worker thật cùng job chỉ một worker gọi AI; DB không giữ khóa trong khi provider đang chờ. */
    @Test void concurrentDecisionWorkersDoNotDuplicateProviderCall() throws Exception {
        var job=transcribedFixture(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var provider=mock(OralDecisionProvider.class);
        when(provider.decide(any())).thenAnswer(call -> { entered.countDown(); if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timeout"); return com.avies.backend.service.support.OralDecisionResult.advance(); });
        var worker=new com.avies.backend.service.support.OralDecisionWorker(decisionSettings,decisionRepository,service,provider,null);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> worker.process(exam,job));
            try { assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue(); pool.submit(() -> worker.process(exam,job)).get(10,TimeUnit.SECONDS); }
            finally { release.countDown(); }
            first.get(10,TimeUnit.SECONDS);
        }
        verify(provider,times(1)).decide(any()); student(); state=service.get(exam); assertThat(state.currentQuestionOrder()).isEqualTo(2); assertThat(count("oral_exam_turns")).isEqualTo(2);
    }

    /** Chỉ đủ cả ba dịch vụ/migration mới công bố LIVE, không dựa cờ VITE. */
    @Test void runtimeOpensLiveOnlyWithCompleteConfiguration() throws Exception {
        when(speechSettings.ready()).thenReturn(true); when(sttSettings.ready()).thenReturn(true); when(decisionSettings.ready()).thenReturn(true);
        mvc.perform(as(get("/oral-examination/runtime"),"student")).andExpect(status().isOk()).andExpect(jsonPath("$.result.mode").value("LIVE")).andExpect(jsonPath("$.result.providersReady").value(true));
        when(decisionSettings.isEnabled()).thenReturn(true); when(sttSettings.ready()).thenReturn(false);
        mvc.perform(as(post(url()).contentType(MediaType.APPLICATION_JSON).content(startBody()),"student")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1815));
        assertThat(count("oral_exam_progress")).isZero(); assertThat(count("student_exam_questions")).isZero();
    }
    /** Khi bật oral, endpoint Start Feature 2 không còn trả toàn bộ đề cho Student. */
    @Test void legacyStartCannotExposeQuestionListWhenOralEnabled() throws Exception {
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(lifecycle);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"oralEnabled",true);
        try {
            mvc.perform(as(post("/student-exams/"+exam+"/starts").contentType(MediaType.APPLICATION_JSON).content("{}"),"student"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1801));
            assertThat(count("student_exam_questions")).isZero();
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(target,"oralEnabled",false); }
    }
    /** Tắt flag để bảo trì không mở đường vòng lấy cả đề của lượt oral đã tạo. */
    @Test void existingOralAttemptRemainsPrivateWhenAiIsDisabled() throws Exception {
        open();
        mvc.perform(as(post("/student-exams/"+exam+"/starts").contentType(MediaType.APPLICATION_JSON).content("{}"),"student"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1801))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_MAIN_PROMPT"))));
    }
    /** Transcript fixture đi qua callback tin cậy, không có endpoint nhập text giả trong production. */
    private UUID transcribedFixture() {
        open(); listen(); command(OralExamAction.SUBMIT); UUID job=state.submissionId(); providerTranscript(job,"Câu trả lời STT thật fixture"); return job;
    }

    /** Multipart thật chốt hash một lần; retry không tạo thêm job, đổi bytes bị 409. */
    @Test void audioUploadIsOwnedDurableAndIdempotent() throws Exception {
        when(sttSettings.ready()).thenReturn(true); open(); listen(); command(OralExamAction.SUBMIT);
        UUID job=state.submissionId(); byte[] bytes=com.avies.backend.service.impl.GroqOralTranscriptionProviderTest.audio();
        for(int i=0;i<2;i++) mvc.perform(as(multipart(url()+"/submissions/"+job+"/audio")
                .file(new org.springframework.mock.web.MockMultipartFile("metadata","metadata.json","application/json",audioMetadata().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .file(new org.springframework.mock.web.MockMultipartFile("audio","../../unsafe.wav","audio/wav",bytes)),"student"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.result.audioReceived").value(true));
        assertThat(java.nio.file.Files.exists(audioDirectory.resolve(job+".audio"))).isTrue();
        assertThat(count("oral_answer_submissions")).isEqualTo(1); verifyNoInteractions(sttProvider);
        byte[] changed=bytes.clone(); changed[200]=1;
        mvc.perform(as(multipart(url()+"/submissions/"+job+"/audio")
                .file(new org.springframework.mock.web.MockMultipartFile("metadata","metadata.json","application/json",audioMetadata().getBytes()))
                .file(new org.springframework.mock.web.MockMultipartFile("audio","answer.wav","audio/wav",changed)),"student"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1814));
        assertThat(audioRepository.ready()).isTrue(); assertThat(answerCount()).isZero();
    }

    /** Claim hai worker không trùng; callback cùng claim chỉ ghi transcript thật một lần, không tự chuyển câu. */
    @Test void sttClaimsAndResultPersistenceAreAtomic() {
        open(); listen(); command(OralExamAction.SUBMIT); var job=state.submissionId(); attachFixture(job);
        var work=service.claimTranscription(exam,job); assertThat(work).isNotNull(); assertThat(service.claimTranscription(exam,job)).isNull();
        service.completeTranscription(new com.avies.backend.service.support.OralTranscriptionWork(exam,job,UUID.randomUUID(),"vi",work.sha256()),"WRONG_NONCE",null);
        assertThat(answerCount()).isZero();
        service.completeTranscription(work,"Em trả lời bằng giọng nói thực tế.",null); service.completeTranscription(work,"LATE_CHANGED_TEXT",null);
        student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.DECIDING); assertThat(answerCount()).isEqualTo(1);
        assertThat(state.transcripts().getFirst().content()).isEqualTo("Em trả lời bằng giọng nói thực tế."); assertThat(state.audioReceived()).isTrue();
    }

    /** STT rỗng quay cùng lượt, quota lỗi giữ job và không sinh transcript giả; retry không reset budget. */
    @Test void emptyAndFailedSttStayDistinctAndRetrySameAudio() {
        open(); listen(); at(fixture.base.start.plusSeconds(5)); command(OralExamAction.SUBMIT); var first=state.submissionId(); attachFixture(first);
        var work=service.claimTranscription(exam,first); service.completeTranscription(work,"",null);
        student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.ANSWERING); assertThat(answerCount()).isZero();
        assertThat(state.answerRemainingMs()).isEqualTo(85000L); command(OralExamAction.SUBMIT); var second=state.submissionId(); attachFixture(second);
        var fail=service.claimTranscription(exam,second); service.completeTranscription(fail,null,1812);
        student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.TECHNICAL_ERROR); assertThat(state.processingErrorCode()).isEqualTo(1812);
        command(OralExamAction.RETRY); var retry=service.claimTranscription(exam,second); assertThat(retry.claimId()).isNotEqualTo(fail.claimId());
        service.completeTranscription(retry,"Em không biết",null); student(); state=service.get(exam);
        assertThat(state.phase()).isEqualTo(OralExamPhase.DECIDING); assertThat(state.answerRemainingMs()).isEqualTo(85000L); assertThat(answerCount()).isEqualTo(1);
    }

    /** Hết slot thắng kết quả về muộn, dù audio đã nhận trước đó. */
    @Test void lateSttCannotReviveExpiredAttempt() {
        open(); listen(); command(OralExamAction.SUBMIT); var job=state.submissionId(); attachFixture(job); var work=service.claimTranscription(exam,job);
        at(fixture.base.start.plusMinutes(21)); service.completeTranscription(work,"TOO_LATE",null);
        student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.EXPIRED); assertThat(answerCount()).isZero();
    }

    /** Nhiều developer cùng Supabase: worker chỉ thấy job của vùng lưu đang sở hữu audio. */
    @Test void workerStorageIdentityPreventsAnotherBackendTakingLocalAudio() {
        open(); listen(); command(OralExamAction.SUBMIT); var job=state.submissionId(); attachFixture(job);
        assertThat(audioRepository.candidates(UUID.randomUUID().toString())).isEmpty();
        assertThat(audioRepository.candidates(audioStore.storageId())).extracting(com.avies.backend.repository.OralAudioRepository.Candidate::submissionId).contains(job);
        jdbc.update("UPDATE aives.oral_answer_submissions SET audio_storage_id=? WHERE id=?",UUID.randomUUID().toString(),job);
        assertThat(service.claimTranscription(exam,job)).isNull(); assertThat(answerCount()).isZero();
    }

    /** Worker lấy file thật ở spool và chỉ provider stub chạy ngoài transaction DB. */
    @Test void actualWorkerReadsStoredAudioAndCommitsTranscriptOutsideNetworkTransaction() {
        open(); listen(); command(OralExamAction.SUBMIT); var job=state.submissionId(); attachFixture(job);
        when(sttProvider.transcribe(any(),eq("vi"))).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return "Bản trả lời từ audio đã được lưu.";
        });
        var worker=new com.avies.backend.service.support.OralTranscriptionWorker(sttSettings,audioRepository,service,audioStore,sttProvider,null);
        worker.process(exam,job); worker.process(exam,job);
        student(); state=service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.DECIDING); assertThat(answerCount()).isEqualTo(1);
        verify(sttProvider,times(1)).transcribe(any(),eq("vi"));
    }

    /** Preview không mở bài thi, vai trò khác và JSON metadata sai không được đưa audio vào job. */
    @Test void transcriptionPreviewDoesNotWriteExamEvidence() throws Exception {
        when(sttSettings.ready()).thenReturn(true); when(sttProvider.transcribe(any(),eq("vi"))).thenReturn("Em đang kiểm tra micro.");
        var audio=new org.springframework.mock.web.MockMultipartFile("audio","answer.wav","audio/wav",com.avies.backend.service.impl.GroqOralTranscriptionProviderTest.audio());
        mvc.perform(as(multipart("/oral-examination/transcription-preview").file(audio).param("language","vi"),"student"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.transcript").value("Em đang kiểm tra micro."));
        mvc.perform(as(multipart("/oral-examination/transcription-preview").file(audio).param("language","vi"),"owner")).andExpect(status().isForbidden());
        mvc.perform(as(multipart("/oral-examination/transcription-preview").file(audio).param("language","fr"),"student")).andExpect(status().isBadRequest());
        assertThat(count("oral_exam_progress")).isZero(); assertThat(answerCount()).isZero();
    }

    /** Metadata không chứa transcript, thời gian tự khai hoặc đường dẫn local. */
    private String audioMetadata() { return "{\"clientSessionId\":\""+client+"\",\"turnId\":\""+state.currentTurnId()+"\"}"; }
    /** Chỉ file fixture trong temp dir; callback claim sau đó dùng service production thật. */
    private void attachFixture(UUID job) {
        student(); state=service.attachAudio(exam,job,new OralAudioRequest(client,state.currentTurnId()),
                com.avies.backend.service.support.OralAudioPayload.detect(com.avies.backend.service.impl.GroqOralTranscriptionProviderTest.audio()));
    }

    /** Audio HTTP đọc snapshot, không giữ khóa/transaction khi gọi provider, nghe lại dùng cache. */
    @Test void speechHttpUsesSnapshotAndReusesAudioWithoutResettingTimers() throws Exception {
        when(speechSettings.ready()).thenReturn(true); open();
        var initial = state; long used = jdbc.queryForObject("SELECT answer_used_ms FROM aives.oral_question_progress WHERE student_exam_id=? AND question_order=1", Long.class, exam);
        when(speechProvider.synthesize(anyString(), anyString())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(call.<String>getArgument(0)).isEqualTo("Question number 1. PRIVATE_MAIN_PROMPT");
            return com.avies.backend.service.impl.GeminiOralSpeechProviderTest.wav();
        });
        jdbc.update("UPDATE aives.questions SET content='CHANGED_LIVE_BANK'");
        String body = "{\"clientSessionId\":\"" + client + "\",\"turnId\":\"" + initial.currentTurnId() + "\",\"expectedRevision\":" + initial.revision() + "}";
        for (int i = 0; i < 2; i++) mvc.perform(as(post(url() + "/speech").contentType(MediaType.APPLICATION_JSON).content(body), "student"))
                .andExpect(status().isOk()).andExpect(content().contentType("audio/wav"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(com.avies.backend.service.impl.GeminiOralSpeechProviderTest.wav()));
        student(); state = service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.SPEAKING);
        assertThat(state.followUpsUsed()).isZero(); assertThat(state.currentTurnId()).isEqualTo(initial.currentTurnId());
        assertThat(jdbc.queryForObject("SELECT answer_used_ms FROM aives.oral_question_progress WHERE student_exam_id=? AND question_order=1", Long.class, exam)).isEqualTo(used);
        verify(speechProvider, times(1)).synthesize(anyString(), eq("en"));
        mvc.perform(as(post(url() + "/speech").contentType(MediaType.APPLICATION_JSON).content(body), "owner")).andExpect(status().isForbidden());
        mvc.perform(post(url() + "/speech").with(jwt().jwt(t -> t.subject("student2")).authorities(new SimpleGrantedAuthority("ROLE_STUDENT")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        student(); command(OralExamAction.PROMPT_FINISHED);
        mvc.perform(as(post(url() + "/speech").contentType(MediaType.APPLICATION_JSON).content(body), "student")).andExpect(status().isConflict());
        verifyNoMoreInteractions(speechProvider);
    }

    /** Hết slot trong lúc TTS xử lý thì không được trả audio hoặc mở giờ trả lời mới. */
    @Test void speechRechecksDeadlineAfterProviderReturns() throws Exception {
        when(speechSettings.ready()).thenReturn(true); open();
        when(speechProvider.synthesize(anyString(), anyString())).thenAnswer(call -> {
            at(fixture.base.start.plusMinutes(21)); return com.avies.backend.service.impl.GeminiOralSpeechProviderTest.wav();
        });
        String body = "{\"clientSessionId\":\"" + client + "\",\"turnId\":\"" + state.currentTurnId() + "\",\"expectedRevision\":" + state.revision() + "}";
        mvc.perform(as(post(url() + "/speech").contentType(MediaType.APPLICATION_JSON).content(body), "student"))
                .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        student(); assertThat(service.get(exam).phase()).isEqualTo(OralExamPhase.EXPIRED); assertThat(answerCount()).isZero();
    }

    /** Preview chỉ đọc mẫu cố định; body ngoài hợp đồng hoặc ngôn ngữ sai không đi tới provider. */
    @Test void speechPreviewValidatesLanguageAndDoesNotStartAnExam() throws Exception {
        when(speechSettings.ready()).thenReturn(true);
        when(speechProvider.synthesize(anyString(), anyString())).thenReturn(com.avies.backend.service.impl.GeminiOralSpeechProviderTest.wav());
        mvc.perform(as(post("/oral-examination/speech-preview").contentType(MediaType.APPLICATION_JSON).content("{\"language\":\"vi\"}"), "student"))
                .andExpect(status().isOk()).andExpect(content().contentType("audio/wav"));
        for (String body : List.of("{\"language\":\"fr\"}", "{\"language\":\"vi\",\"text\":\"USER_TEXT\"}"))
            mvc.perform(as(post("/oral-examination/speech-preview").contentType(MediaType.APPLICATION_JSON).content(body), "student")).andExpect(status().isBadRequest());
        assertThat(count("oral_exam_progress")).isZero(); verify(speechProvider).synthesize(startsWith("Câu số 1. Xin chào."), eq("vi"));
    }

    /** Mở lần đầu tạo đúng dữ liệu; refresh không sinh lại câu, sửa snapshot hoặc cộng giờ. */
    @Test void openingAndReloadingPreserveSnapshotsAndTurnIdentity() throws Exception {
        open(); UUID turn = state.currentTurnId(); var deadline = state.deadline();
        var snapshots = jdbc.queryForList("SELECT * FROM aives.student_exam_questions WHERE student_exam_id=? ORDER BY id", exam);
        jdbc.update("UPDATE aives.questions SET content='CHANGED',reference_answer='CHANGED',version=version+1");
        state = service.open(exam, new OralExamStartRequest(client));
        assertThat(state.currentTurnId()).isEqualTo(turn); assertThat(state.deadline()).isEqualTo(deadline);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.oral_exam_turns", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM aives.student_exam_questions WHERE student_exam_id=? ORDER BY id", exam)).isEqualTo(snapshots);
        mvc.perform(as(get(url()), "student")).andExpect(status().isOk()).andExpect(jsonPath("$.result.phase").value("PREPARING"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_MAIN_PROMPT"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_REFERENCE_ANSWER"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_RUBRIC"))));
    }
    /** Trước giờ/đúng hạn cuối không được mở mới, toàn bộ trạng thái ban đầu giữ nguyên. */
    @ParameterizedTest @ValueSource(strings = {"before", "end"}) void cannotOpenOutsideSlot(String when) throws Exception {
        at(when.equals("before") ? fixture.base.start.minusSeconds(1) : fixture.base.start.plusMinutes(20));
        mvc.perform(as(post(url()).contentType(MediaType.APPLICATION_JSON).content(startBody()), "student")).andExpect(status().isConflict());
        assertThat(count("oral_exam_progress")).isZero(); assertThat(count("student_exam_questions")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, exam)).isEqualTo("ASSIGNED");
    }
    /** Token Lecturer/Admin không được dùng API Student, Student khác không được dò lượt người khác. */
    @ParameterizedTest @ValueSource(strings = {"owner", "admin", "student2"}) void rejectsWrongRoleOrOwner(String actor) throws Exception {
        var request = post(url()).contentType(MediaType.APPLICATION_JSON).content(startBody());
        if (actor.equals("student2")) request.with(jwt().jwt(token -> token.subject(actor)).authorities(new SimpleGrantedAuthority("ROLE_STUDENT")));
        else as(request, actor);
        mvc.perform(request).andExpect(status().is(actor.equals("student2") ? 404 : 403));
        assertThat(count("oral_exam_progress")).isZero();
    }
    /** JWT authority cũ không vượt được role hiện hành trong database. */
    @Test void staleRoleCannotOpenProgress() throws Exception {
        jdbc.update("UPDATE aives.users SET role_id=2 WHERE id=4");
        mvc.perform(as(post(url()).contentType(MediaType.APPLICATION_JSON).content(startBody()), "student")).andExpect(status().isForbidden());
    }
    /** Production báo rõ chưa nối dịch vụ và không mở đường mô phỏng ghi kết quả. */
    @Test void productionRuntimeHasNoMockProviderOrDemoRoutes() throws Exception {
        mvc.perform(as(get("/oral-examination/runtime"), "student"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.mode").value("FOUNDATION"))
                .andExpect(jsonPath("$.result.providersReady").value(false));
        mvc.perform(as(get("/oral-examination/runtime"), "owner")).andExpect(status().isForbidden());
        SecurityContextHolder.clearContext();
        mvc.perform(get("/oral-examination/runtime")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isUnauthorized());
        mvc.perform(as(post("/_oral-demo/student-exams/" + exam + "/scenario")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), "student"))
                .andExpect(status().isNotFound());
        assertThat(count("oral_exam_progress")).isZero();
    }
    /** Body Student không nhận transcript/điểm hoặc cấu hình ngân sách tự khai. */
    @ParameterizedTest @ValueSource(strings = {"transcript", "maxFollowUps", "answerBudgetMs", "score"}) void rejectsUnknownStartFields(String field) throws Exception {
        mvc.perform(as(post(url()).contentType(MediaType.APPLICATION_JSON).content("{\"clientSessionId\":\"" + client + "\",\"" + field + "\":1}"), "student"))
                .andExpect(status().isBadRequest());
        assertThat(count("oral_exam_progress")).isZero();
    }
    /** Đường ghi transcript hoặc kết quả provider không tồn tại dưới HTTP Student. */
    @Test void studentCannotPostProviderResults() throws Exception {
        open();
        mvc.perform(as(post(url() + "/transcripts").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Tự khai\"}"), "student"))
                .andExpect(status().isNotFound());
        mvc.perform(as(post(url() + "/actions").contentType(MediaType.APPLICATION_JSON).content(commandBody("PROMPT_FINISHED", "\"transcript\":\"Tự khai\"")), "student"))
                .andExpect(status().isBadRequest());
        assertThat(answerCount()).isZero();
    }
    /** Mã lệnh lặp không mở lại đồng hồ, tạo receipt hoặc submission lần hai. */
    @Test void repeatedCommandsAndProviderCallbacksHaveOneEffect() {
        open(); ready(); var command = request(OralExamAction.PROMPT_FINISHED, null);
        state = service.execute(exam, command); at(fixture.base.start.plusSeconds(5)); state = service.execute(exam, command);
        assertThat(state.answerRemainingMs()).isEqualTo(85000L); assertThat(count("oral_exam_commands")).isEqualTo(1);
        command(OralExamAction.SUBMIT); UUID submission = state.submissionId();
        providerTranscript(submission, "Câu trả lời thực tế của fixture"); providerTranscript(submission, "Câu trả lời thực tế của fixture");
        state = service.decide(exam, submission, true, "PRIVATE_FOLLOW_UP"); state = service.decide(exam, submission, true, "PRIVATE_FOLLOW_UP");
        assertThat(answerCount()).isEqualTo(1); assertThat(count("oral_answer_submissions")).isEqualTo(1);
        assertThat(count("oral_exam_turns")).isEqualTo(2); assertThat(state.followUpsUsed()).isEqualTo(1);
    }
    /** requestId không được tái sử dụng với payload khác, kể cả sau khi lệnh đầu đã thành công. */
    @Test void sameRequestIdWithDifferentPayloadIsConflict() {
        open(); ready(); var original = request(OralExamAction.PROMPT_FINISHED, null); state = service.execute(exam, original);
        var altered = new OralExamCommandRequest(original.requestId(), client, state.revision(), state.currentTurnId(), OralExamAction.REPLAY, null);
        assertCode(() -> service.execute(exam, altered), 1802);
        assertThat(count("oral_exam_commands")).isEqualTo(1);
    }
    /** Client cầm revision/turn cũ không được áp lệnh vào câu mới. */
    @Test void staleCommandCannotAffectNextTurn() {
        open(); ready(); var stale = request(OralExamAction.PROMPT_FINISHED, null);
        command(OralExamAction.PROMPT_FINISHED); answer(false); student();
        assertCode(() -> service.execute(exam, stale), 1802); assertThat(state.currentQuestionOrder()).isEqualTo(2);
    }
    /** Một tab giữ lease; tab mới chỉ nhận quyền sau khi hết hạn và không được cấp lại ngân sách. */
    @Test void anotherTabCannotControlUntilLeaseExpires() {
        open(); listen(); UUID secondClient = UUID.randomUUID();
        assertCode(() -> service.open(exam, new OralExamStartRequest(secondClient)), 1803);
        at(fixture.base.start.plusSeconds(46)); state = service.open(exam, new OralExamStartRequest(secondClient));
        assertThat(state.phase()).isEqualTo(OralExamPhase.PAUSED); assertThat(state.answerRemainingMs()).isEqualTo(44000L);
        assertCode(() -> service.execute(exam, request(OralExamAction.HEARTBEAT, null)), 1803);
        client = secondClient; command(OralExamAction.RESUME); assertThat(state.phase()).isEqualTo(OralExamPhase.SPEAKING);
    }
    /** Nhịp giữ quyền không làm tăng revision hoặc gia hạn deadline/ngân sách. */
    @Test void heartbeatDoesNotResetTimersOrRevision() {
        open(); listen(); Long revision = state.revision(); var deadline = state.deadline();
        at(fixture.base.start.plusSeconds(20)); command(OralExamAction.HEARTBEAT);
        assertThat(state.revision()).isEqualTo(revision); assertThat(state.answerRemainingMs()).isEqualTo(70000L); assertThat(state.deadline()).isEqualTo(deadline);
    }
    /** Hai Student kế tiếp dùng cùng ngân sách follow-up theo từng lượt, không theo từng câu. */
    @Test void globalFollowUpLimitAndSequentialMainQuestions() {
        open(); listen(); answer(true); assertThat(state.followUpsUsed()).isEqualTo(1); assertThat(state.followUpIndex()).isEqualTo(1);
        listen(); answer(true); assertThat(state.followUpsUsed()).isEqualTo(2); listen(); answer(true);
        assertThat(state.currentQuestionOrder()).isEqualTo(2); assertThat(state.followUpsUsed()).isEqualTo(2);
        listen(); answer(true); assertThat(state.phase()).isEqualTo(OralExamPhase.COMPLETED); assertThat(state.status()).isEqualTo(StudentExamStatus.COMPLETED);
        assertThat(answerCount()).isEqualTo(4); assertThat(count("oral_exam_turns")).isEqualTo(4);
    }
    /** Bỏ qua được quay lại đúng turn; câu đã trả lời không được sửa hoặc quay lại. */
    @Test void deferredQueuePreservesTimeAndIdentity() {
        open(); listen(); UUID first = state.currentTurnId(); at(fixture.base.start.plusSeconds(10)); command(OralExamAction.DEFER);
        assertCode(() -> service.execute(exam, request(OralExamAction.REVISIT, 1)), 1801);
        listen(); answer(false); assertThat(state.phase()).isEqualTo(OralExamPhase.REVIEW);
        command(OralExamAction.REVISIT, 1); assertThat(state.currentTurnId()).isEqualTo(first); assertThat(state.answerRemainingMs()).isEqualTo(80000L);
        command(OralExamAction.PROMPT_FINISHED); answer(false);
        assertThat(state.status()).isEqualTo(StudentExamStatus.COMPLETED); assertThat(answerCount()).isEqualTo(2);
    }
    /** Nộp rõ ràng khi còn DEFERRED được chấp nhận bằng outcome, không thêm transcript rỗng. */
    @Test void explicitFinishAcceptsDeferredOutcomesWithoutFakeTranscripts() {
        open(); listen(); command(OralExamAction.DEFER); listen(); answer(false);
        command(OralExamAction.FINISH); assertThat(state.status()).isEqualTo(StudentExamStatus.COMPLETED);
        assertThat(state.questions().getFirst().status()).isEqualTo(OralQuestionStatus.NO_ANSWER);
        assertThat(state.questions().getFirst().outcomeReason()).isEqualTo("SUBMITTED_UNANSWERED");
        assertThat(answerCount()).isEqualTo(1); assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.transcripts WHERE btrim(content)=''", Integer.class)).isZero();
    }
    /** Im lặng hết ngân sách chuyển câu, và Finish chỉ công nhận outcome do BE lưu. */
    @Test void silenceTimeoutThenAnswerCompletesWithoutEmptyTranscript() {
        open(); listen(); at(fixture.base.start.plusSeconds(90)); state = service.get(exam);
        assertThat(state.currentQuestionOrder()).isEqualTo(2); assertThat(state.questions().getFirst().status()).isEqualTo(OralQuestionStatus.NO_ANSWER);
        state = service.open(exam, new OralExamStartRequest(client)); listen(); answer(false);
        assertThat(state.status()).isEqualTo(StudentExamStatus.COMPLETED); assertThat(answerCount()).isEqualTo(1);
    }
    /** Audio rỗng quay lại cùng câu, không tự tạo bằng chứng; retry sau đó lưu đúng một câu trả lời. */
    @Test void emptyAudioDoesNotCountAsAnswer() {
        open(); listen(); command(OralExamAction.SUBMIT); UUID empty = state.submissionId(); providerTranscript(empty, " ");
        assertThat(state.phase()).isEqualTo(OralExamPhase.ANSWERING); assertThat(answerCount()).isZero();
        command(OralExamAction.SUBMIT); providerTranscript(state.submissionId(), "Em không biết");
        assertThat(state.phase()).isEqualTo(OralExamPhase.DECIDING); assertThat(answerCount()).isEqualTo(1);
    }
    /** Lỗi STT và lỗi AI retry cùng job, không trừ ngân sách khi xử lý. */
    @Test void technicalRetryPreservesJobAndStoredEvidence() {
        open(); listen(); command(OralExamAction.SUBMIT); UUID submission = state.submissionId();
        SecurityContextHolder.clearContext(); state = service.technicalFailure(exam, submission); student(); command(OralExamAction.RETRY);
        assertThat(state.submissionId()).isEqualTo(submission); providerTranscript(submission, "Câu trả lời đã lưu");
        state = service.technicalFailure(exam, submission); student(); command(OralExamAction.RETRY);
        assertThat(state.phase()).isEqualTo(OralExamPhase.DECIDING); assertThat(answerCount()).isEqualTo(1);
        state = service.decide(exam, submission, false, null); assertThat(state.currentQuestionOrder()).isEqualTo(2);
        assertThat(count("oral_answer_submissions")).isEqualTo(1);
    }
    /** Callback quá hạn không chèn transcript, không gia hạn hoặc giả thành COMPLETED. */
    @Test void lateCallbackExpiresAttemptAndPreservesTechnicalOutcome() {
        open(); listen(); command(OralExamAction.SUBMIT); UUID id = state.submissionId(); at(fixture.base.start.plusMinutes(20));
        providerTranscript(id, "Đến quá muộn"); assertThat(state.status()).isEqualTo(StudentExamStatus.EXPIRED); assertThat(state.phase()).isEqualTo(OralExamPhase.EXPIRED);
        assertThat(state.questions().getFirst().status()).isEqualTo(OralQuestionStatus.TECHNICAL_ERROR); assertThat(answerCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT completed_at FROM aives.student_exams WHERE id=?", OffsetDateTime.class, exam)).isNull();
    }
    /** Lệnh Submit đúng biên hết thời gian không được gán âm thanh cho câu tiếp theo. */
    @Test void lateStudentCommandDoesNotSubmitForNextQuestion() {
        open(); listen(); var request = request(OralExamAction.SUBMIT, null); at(fixture.base.start.plusSeconds(90)); state = service.execute(exam, request);
        assertThat(state.currentQuestionOrder()).isEqualTo(2); assertThat(count("oral_answer_submissions")).isZero();
    }
    /** Finish Feature 2 không được vượt qua follow-up đang mở dù đã có câu trả lời chính. */
    @Test void directFinishCannotBypassActiveFollowUp() throws Exception {
        jdbc.update("UPDATE aives.exam_sessions SET max_main_questions=1 WHERE id=?", parent); open(); listen(); answer(true);
        mvc.perform(as(post("/student-exams/" + exam + "/completions"), "student")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1703));
        assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, exam)).isEqualTo("IN_PROGRESS");
    }
    /** Không tự nhận các transcript legacy là hội thoại mới; lỗi mở rollback mọi thay đổi mới. */
    @Test void refusesToFabricateProgressForLegacyConversation() {
        lifecycle.start(exam); long question = jdbc.queryForObject("SELECT min(id) FROM aives.student_exam_questions WHERE student_exam_id=?", Long.class, exam);
        jdbc.update("INSERT INTO aives.transcripts(student_exam_question_id,turn_no,turn_type,content) VALUES (?,1,'STUDENT_ANS','Legacy')", question);
        assertCode(() -> service.open(exam, new OralExamStartRequest(client)), 1804); assertThat(count("oral_exam_progress")).isZero();
    }
    /** Một lỗi ghi giữa pipeline phải rollback cả transcript, state, counters và receipt. */
    @Test void transcriptWriteFailureRollsBackWholeCallback() {
        open(); listen(); command(OralExamAction.SUBMIT); UUID submission = state.submissionId();
        jdbc.execute("CREATE FUNCTION aives.reject_oral_answer() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.turn_type='STUDENT_ANS' THEN RAISE EXCEPTION 'test' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_oral_answer BEFORE INSERT ON aives.transcripts FOR EACH ROW EXECUTE FUNCTION aives.reject_oral_answer()");
        try { assertCode(() -> service.transcriptReady(exam, submission, "Không được lưu một phần"), 1303); }
        finally { jdbc.execute("DROP TRIGGER reject_oral_answer ON aives.transcripts"); jdbc.execute("DROP FUNCTION aives.reject_oral_answer()"); }
        student(); state = service.get(exam); assertThat(state.phase()).isEqualTo(OralExamPhase.TRANSCRIBING); assertThat(answerCount()).isZero();
        providerTranscript(submission, "Lưu sau khi hết lỗi"); assertThat(answerCount()).isEqualTo(1);
    }
    /** Hai request cùng receipt được tuần tự hóa; xung đột có thể retry mà không tăng quota. */
    @Test void concurrentDuplicateSubmissionHasOnlyOnePersistedEffect() throws Exception {
        open(); listen(); var command = request(OralExamAction.SUBMIT, null);
        var latch = new CountDownLatch(1); var release = new CountDownLatch(1); var pool = Executors.newSingleThreadExecutor();
        Future<?> first = pool.submit(() -> {
            student(); new TransactionTemplate(manager).executeWithoutResult(status -> {
                service.execute(exam, command); latch.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test wait"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }); SecurityContextHolder.clearContext();
        });
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); student(); assertCode(() -> service.execute(exam, command), 1303);
        } finally { release.countDown(); first.get(10, TimeUnit.SECONDS); pool.shutdownNow(); }
        student(); state = service.execute(exam, command); assertThat(count("oral_answer_submissions")).isEqualTo(1); assertThat(count("oral_exam_commands")).isEqualTo(2);
    }
    /** FK/ràng buộc thật chống gán tiến trình vào câu của lượt khác và xóa bằng chứng. */
    @Test void databaseProtectsCrossAttemptReferencesAndRetainedEvidence() {
        open();
        assertThatThrownBy(() -> jdbc.update("UPDATE aives.oral_question_progress SET student_exam_id=?", exam + 100)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM aives.student_exam_questions WHERE student_exam_id=?", exam)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='aives' AND c.relname IN ('oral_exam_progress','oral_question_progress','oral_exam_turns','oral_answer_submissions','oral_exam_commands') AND c.relrowsecurity", Integer.class)).isEqualTo(5);
    }
    /** Close vẫn phân biệt EXPIRED/COMPLETED và giữ toàn bộ evidence mới. */
    @Test void closingSessionAndReadingProgressKeepsEvidenceAndExpiry() {
        open(); listen(); answer(false); long transcripts = count("transcripts");
        at(fixture.base.start.plusHours(2)); authenticate("owner", "LECTURER"); lifecycle.close(parent);
        student(); state = service.get(exam); assertThat(state.status()).isEqualTo(StudentExamStatus.EXPIRED); assertThat(state.phase()).isEqualTo(OralExamPhase.EXPIRED);
        assertThat(count("transcripts")).isEqualTo(transcripts); assertThat(state.transcripts()).hasSize(1);
    }

    /** Khi migration chưa sẵn sàng, API mới trả lỗi có kiểm soát và không khởi động lượt thi. */
    @Test void missingMigrationReturnsControlledUnavailableWithoutStartingExam() throws Exception {
        jdbc.execute("ALTER TABLE aives.oral_exam_progress RENAME TO oral_progress_unavailable_test");
        try {
            mvc.perform(as(post(url()).contentType(MediaType.APPLICATION_JSON).content(startBody()), "student"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1806));
            assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, exam)).isEqualTo("ASSIGNED");
        } finally { jdbc.execute("ALTER TABLE aives.oral_progress_unavailable_test RENAME TO oral_exam_progress"); }
    }

    /** Finish không nhận bằng chứng ANSWERED bị xóa khỏi transcript sau khi STT đã ghi. */
    @Test void missingTranscriptCannotBeHiddenByProgressFlags() {
        open(); listen(); answer(false); listen(); command(OralExamAction.SUBMIT); var id = state.submissionId();
        providerTranscript(id, "Câu trả lời sau cùng");
        jdbc.update("DELETE FROM aives.transcripts WHERE turn_type='STUDENT_ANS' AND student_exam_question_id=(SELECT min(student_exam_question_id) FROM aives.oral_question_progress)");
        assertCode(() -> service.decide(exam, id, false, null), 1703);
        assertThat(jdbc.queryForObject("SELECT phase FROM aives.oral_exam_progress WHERE student_exam_id=?", String.class, exam)).isEqualTo("DECIDING");
        assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, exam)).isEqualTo("IN_PROGRESS");
    }

    /** Ngân sách chốt từ snapshot, không thay theo ngân hàng hoặc form demo FE. */
    @Test void questionBudgetComesFromFrozenSnapshot() {
        open(); jdbc.update("UPDATE aives.questions SET answer_time_limit_seconds=999,content='Changed' ");
        state = service.get(exam); assertThat(state.answerRemainingMs()).isEqualTo(90000L);
        assertThat(jdbc.queryForObject("SELECT prompt_text FROM aives.oral_exam_turns WHERE id=?", String.class, state.currentTurnId())).isEqualTo("PRIVATE_MAIN_PROMPT");
    }

    /** Tab mới không được dùng heartbeat nền để chiếm quyền của tab cũ đã hết lease. */
    @Test void heartbeatCannotImplicitlyTakeOverAnotherClient() {
        open(); listen(); at(fixture.base.start.plusSeconds(46));
        var foreign = new OralExamCommandRequest(UUID.randomUUID(), UUID.randomUUID(), state.revision(), state.currentTurnId(), OralExamAction.HEARTBEAT, null);
        assertCode(() -> service.execute(exam, foreign), 1803);
        assertThat(jdbc.queryForObject("SELECT client_session_id FROM aives.oral_exam_progress WHERE student_exam_id=?", UUID.class, exam)).isEqualTo(client);
    }

    /** Ba endpoint thật bind JSON hợp lệ, thực thi method security và chỉ trả metadata an toàn. */
    @Test void httpEndpointsBindValidCommandsAndReturnSafeProgress() throws Exception {
        mvc.perform(as(post(url()).contentType(MediaType.APPLICATION_JSON).content(startBody()), "student"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.phase").value("PREPARING"))
                .andExpect(jsonPath("$.result.mainCount").value(2));
        student(); state = service.get(exam); ready();
        mvc.perform(as(post(url() + "/actions").contentType(MediaType.APPLICATION_JSON).content(commandBody("PROMPT_FINISHED", "\"targetQuestionOrder\":null")), "student"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.phase").value("ANSWERING"));
        student(); state = service.get(exam);
        mvc.perform(as(post(url() + "/actions").contentType(MediaType.APPLICATION_JSON).content(commandBody("SUBMIT", "\"targetQuestionOrder\":null")), "student"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.phase").value("TRANSCRIBING"))
                .andExpect(jsonPath("$.result.submissionId").isNotEmpty());
        mvc.perform(as(get(url()), "student")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.transcripts").isEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_MAIN_PROMPT"))));
    }

    /** Mốc giờ giả không ảnh hưởng giờ máy hoặc datasource. */ private void at(OffsetDateTime time) { when(clock.instant()).thenReturn(time.toInstant()); }
    /** Danh tính riêng cho mỗi thread/lời gọi public service. */ private void student() { authenticate("student", "STUDENT"); }
    /** Không dùng token thật trong test. */ private void authenticate(String name, String role) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, "unused", List.of(new SimpleGrantedAuthority("ROLE_" + role)))); }
    /** Endpoint nền móng mới không trùng Start cũ. */ private String url() { return "/student-exams/" + exam + "/oral-examination"; }
    /** Body chỉ có danh tính tab. */ private String startBody() { return "{\"clientSessionId\":\"" + client + "\"}"; }
    /** Mở qua service thật dưới danh tính Student. */ private void open() { student(); state = service.open(exam, new OralExamStartRequest(client)); }
    /** Callback worker không dựa JWT còn sống. */ private void ready() { SecurityContextHolder.clearContext(); state = service.promptReady(exam, state.currentTurnId(), "vi"); student(); }
    /** Đã phát xong mới bắt đầu tính thời gian trả lời. */ private void listen() { ready(); command(OralExamAction.PROMPT_FINISHED); }
    /** Lệnh luôn dựa response gần nhất, mỗi thao tác một requestId. */ private OralExamCommandRequest request(OralExamAction action, Integer target) { return new OralExamCommandRequest(UUID.randomUUID(), client, state.revision(), state.currentTurnId(), action, target); }
    /** Gửi lệnh, không tự chỉnh tiến trình ở fixture. */ private void command(OralExamAction action) { command(action, null); }
    /** Target chỉ được có khi revisit hàng đợi. */ private void command(OralExamAction action, Integer target) { student(); state = service.execute(exam, request(action, target)); }
    /** Pipeline nhà cung cấp giả chỉ nằm trong test; production không có endpoint này. */ private void providerTranscript(UUID id, String text) { SecurityContextHolder.clearContext(); state = service.transcriptReady(exam, id, text); }
    /** Lưu câu trả lời rồi mới quyết định bước kế tiếp. */ private void answer(boolean followUp) { command(OralExamAction.SUBMIT); var id = state.submissionId(); providerTranscript(id, "Câu trả lời fixture đã được STT lưu"); state = service.decide(exam, id, followUp, followUp ? "PRIVATE_FOLLOW_UP" : null); student(); }
    /** Đếm bằng datasource container, không truy cập DB bên ngoài. */ private long count(String table) { if (!Set.of("oral_exam_progress","oral_exam_turns","oral_answer_submissions","oral_exam_commands","student_exam_questions","transcripts").contains(table)) throw new IllegalArgumentException(); return jdbc.queryForObject("SELECT count(*) FROM aives." + table, Long.class); }
    /** Chỉ bằng chứng Student mới được tính là câu trả lời. */ private long answerCount() { return jdbc.queryForObject("SELECT count(*) FROM aives.transcripts WHERE turn_type='STUDENT_ANS'", Long.class); }
    /** Xác minh mã nghiệp vụ, không dựa chi tiết SQL. */ private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int code) { assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class, failure -> assertThat(failure.getErrorCode().getCode()).isEqualTo(code)); }
    /** Body sai trường dùng để kiểm tra schema HTTP chặt chẽ. */ private String commandBody(String action, String extra) { return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"clientSessionId\":\"" + client + "\",\"expectedRevision\":" + state.revision() + ",\"turnId\":\"" + state.currentTurnId() + "\",\"action\":\"" + action + "\"," + extra + "}"; }
}
