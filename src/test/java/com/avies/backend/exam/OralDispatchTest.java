package com.avies.backend.exam;

import com.avies.backend.config.*;
import com.avies.backend.repository.*;
import com.avies.backend.service.*;
import com.avies.backend.service.support.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Giao dịch H2 cô lập và provider giả: không mở Supabase, gọi AI thật hoặc chờ scheduler. */
class OralDispatchTest {
    /** STT/AI chỉ chạy sau commit trên thread riêng; rollback và event ngoài transaction bị bỏ qua. */
    @Test void committedWorkDispatchesImmediatelyWithoutScheduling() throws Exception {
        var oral=mock(OralExamService.class); var stt=mock(OralTranscriptionProvider.class); var ai=mock(OralDecisionProvider.class);
        var store=mock(OralAudioStore.class); var sttSettings=mock(OralTranscriptionProperties.class); var aiSettings=mock(OralDecisionProperties.class);
        when(sttSettings.ready()).thenReturn(true); when(aiSettings.ready()).thenReturn(true);
        var sttJob=UUID.randomUUID(); var aiJob=UUID.randomUUID();
        var sttWork=new OralTranscriptionWork(1,sttJob,UUID.randomUUID(),"vi","hash");
        var aiWork=new OralDecisionWork(1,aiJob,UUID.randomUUID(),"vi","Câu hỏi?",List.of(),true);
        when(oral.claimTranscription(1L,sttJob)).thenReturn(sttWork); when(oral.claimDecision(1L,aiJob)).thenReturn(aiWork);
        var bytes=new OralAudioPayload(new byte[]{1,2},"audio/webm","webm","hash"); when(store.read(sttJob,"hash")).thenReturn(bytes);
        var completed=new CountDownLatch(2); long caller=Thread.currentThread().threadId();
        when(stt.transcribe(bytes,"vi")).thenAnswer(call -> {
            assertThat(Thread.currentThread().threadId()).isNotEqualTo(caller);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return "Trả lời";
        });
        when(ai.decide(aiWork)).thenAnswer(call -> {
            assertThat(Thread.currentThread().threadId()).isNotEqualTo(caller);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return OralDecisionResult.advance();
        });
        doAnswer(call -> { completed.countDown(); return null; }).when(oral).completeTranscription(sttWork,"Trả lời",null);
        doAnswer(call -> { completed.countDown(); return null; }).when(oral).completeDecision(aiWork,OralDecisionResult.advance(),null);
        try (var context=new AnnotationConfigApplicationContext()) {
            context.registerBean(TransactionalEventListenerFactory.class);
            context.registerBean("oralSttExecutor",ThreadPoolTaskExecutor.class,() -> executor("dispatch-stt-"));
            context.registerBean("oralDecisionExecutor",ThreadPoolTaskExecutor.class,() -> executor("dispatch-ai-"));
            context.registerBean(OralTranscriptionWorker.class,() -> new OralTranscriptionWorker(sttSettings,mock(OralAudioRepository.class),oral,store,stt,context.getBean("oralSttExecutor",ThreadPoolTaskExecutor.class)));
            context.registerBean(OralDecisionWorker.class,() -> new OralDecisionWorker(aiSettings,mock(OralDecisionRepository.class),oral,ai,context.getBean("oralDecisionExecutor",ThreadPoolTaskExecutor.class)));
            context.refresh();
            var tx=transaction();
            context.publishEvent(new OralWorkReady(1,sttJob,OralWorkReady.Stage.STT));
            tx.executeWithoutResult(status -> { context.publishEvent(new OralWorkReady(1,aiJob,OralWorkReady.Stage.DECISION)); status.setRollbackOnly(); });
            verifyNoInteractions(stt,ai,oral);
            tx.executeWithoutResult(status -> {
                context.publishEvent(new OralWorkReady(1,sttJob,OralWorkReady.Stage.STT));
                context.publishEvent(new OralWorkReady(1,aiJob,OralWorkReady.Stage.DECISION));
                verifyNoInteractions(stt,ai,oral);
            });
            assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue(); verify(stt).transcribe(bytes,"vi"); verify(ai).decide(aiWork);
        }
    }

    /** Làm nóng audio cũng chờ commit, không gọi khi rollback hoặc deadline đã hết. */
    @Test void speechWarmupRequiresCommitAndUnexpiredDeadline() throws Exception {
        var speech=mock(OralSpeechService.class); var now=Instant.parse("2035-01-01T00:00:00Z");
        var plan=OralSpeechPlan.from(UUID.randomUUID(),"vi",1,0,"Câu hỏi?"); var completed=new CountDownLatch(1);
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); completed.countDown(); return null; }).when(speech).prefetch(plan);
        try (var context=new AnnotationConfigApplicationContext()) {
            context.registerBean(TransactionalEventListenerFactory.class);
            context.registerBean("oralSpeechWarmupExecutor",ThreadPoolTaskExecutor.class,() -> executor("dispatch-tts-"));
            context.registerBean(OralSpeechWarmupWorker.class,() -> new OralSpeechWarmupWorker(speech,context.getBean("oralSpeechWarmupExecutor",ThreadPoolTaskExecutor.class),Clock.fixed(now,ZoneOffset.UTC)));
            context.refresh(); var tx=transaction(); var event=new OralSpeechWarmup(plan,OffsetDateTime.ofInstant(now.plusSeconds(60),ZoneOffset.UTC));
            tx.executeWithoutResult(status -> { context.publishEvent(event); status.setRollbackOnly(); }); verifyNoInteractions(speech);
            tx.executeWithoutResult(status -> { context.publishEvent(new OralSpeechWarmup(plan,OffsetDateTime.ofInstant(now,ZoneOffset.UTC))); });
            tx.executeWithoutResult(status -> { context.publishEvent(event); verifyNoInteractions(speech); });
            assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue(); verify(speech,times(1)).prefetch(plan);
        }
    }
    /** Không bật scheduler trong test: chỉ thực thi event production trên pool hữu hạn. */
    private ThreadPoolTaskExecutor executor(String prefix) {
        var executor=new ThreadPoolTaskExecutor(); executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(0);
        executor.setThreadNamePrefix(prefix); executor.setWaitForTasksToCompleteOnShutdown(true); return executor;
    }
    /** Transaction JDBC thật dùng H2 test sẵn có, không đọc cấu hình ứng dụng. */
    private TransactionTemplate transaction() {
        return new TransactionTemplate(new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID(),"sa","")));
    }
}
