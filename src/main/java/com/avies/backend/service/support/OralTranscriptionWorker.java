package com.avies.backend.service.support;

import com.avies.backend.config.OralTranscriptionProperties;
import com.avies.backend.exception.*;
import com.avies.backend.repository.OralAudioRepository;
import com.avies.backend.service.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.*;
import java.util.concurrent.*;

/** Phục hồi job đã có audio sau restart; lease DB ngăn hai instance cùng chốt kết quả. */
@Component
@lombok.extern.slf4j.Slf4j
public class OralTranscriptionWorker {
    /** Cấu hình mạng. */ private final OralTranscriptionProperties settings;
    /** Chỉ dùng repo để tìm candidate, mutation đi qua service transaction. */ private final OralAudioRepository repository;
    /** Kiểm tra lịch/nonce/turn và ghi bằng chứng nguyên tử. */ private final OralExamService oral;
    /** Spool private. */ private final OralAudioStore store;
    /** Provider STT thật hoặc stub trong test. */ private final OralTranscriptionProvider provider;
    /** Executor hữu hạn. */ private final ThreadPoolTaskExecutor executor;
    /** Giảm việc xếp trùng trong cùng process; DB claim vẫn là nguồn quyết định. */ private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    /** Không bắt đầu thread trong constructor. */
    public OralTranscriptionWorker(OralTranscriptionProperties settings, OralAudioRepository repository, OralExamService oral,
            OralAudioStore store, OralTranscriptionProvider provider, @Qualifier("oralSttExecutor") ThreadPoolTaskExecutor executor) {
        this.settings=settings; this.repository=repository; this.oral=oral; this.store=store; this.provider=provider; this.executor=executor;
    }
    /** Chưa migration/key thì không làm startup lỗi hoặc gọi provider. */
    @Scheduled(fixedDelay=2000) public void recover() {
        if (!settings.ready() || !repository.ready()) return;
        for (var candidate : repository.candidates(store.storageId())) {
            if (!enqueue(candidate.examId(),candidate.submissionId())) break;
        }
    }
    /** Đánh thức ngay sau commit; không chờ nhịp quét hoặc gọi provider trên thread HTTP. */
    @TransactionalEventListener
    public void ready(OralWorkReady event) {
        if (event.stage()==OralWorkReady.Stage.STT && settings.ready()) enqueue(event.examId(),event.submissionId());
    }
    /** Hàng đợi hữu hạn; scheduler sẽ phục hồi khi đầy hoặc process bị dừng. */
    private boolean enqueue(long examId, UUID job) {
        if (!active.add(job)) return true;
        try {
            executor.execute(() -> { try { process(examId,job); } finally { active.remove(job); } });
            return true;
        } catch (RejectedExecutionException full) { active.remove(job); return false; }
    }
    /** Gọi mạng ngoài transaction; lỗi kỹ thuật không tạo transcript rỗng hoặc quyết định follow-up thay AI. */
    public void process(long examId, UUID job) {
        long claimedAt=System.nanoTime();
        OralTranscriptionWork work;
        try { work = oral.claimTranscription(examId, job); } catch (AppException busy) { if (busy.getErrorCode().getCode()!=1303) log.warn("Không nhận được job STT {}, mã {}",job,busy.getErrorCode().getCode()); return; }
        if (work == null) return;
        long claimMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-claimedAt);
        long started=System.nanoTime();
        String text = null; Integer error = null; String stage="READ_AUDIO";
        try { var audio=store.read(job,work.sha256()); stage="GROQ"; text = Objects.requireNonNull(provider.transcribe(audio, work.language())); }
        catch (OralTranscriptionException failure) {
            error=failure.getErrorCode().getCode();
            log.warn("oral-stt exam={} job={} stage={} reason={} httpStatus={} segment={}",examId,job,stage,failure.getReason(),failure.getProviderStatus(),failure.getSegmentIndex());
        }
        catch (AppException failure) {
            int code = failure.getErrorCode().getCode(); error = Set.of(1810,1811,1812,1813).contains(code) ? code : 1811;
            log.warn("oral-stt exam={} job={} stage={} code={}",examId,job,stage,code);
        }
        catch (RuntimeException failure) { error = 1811; log.warn("oral-stt exam={} job={} stage={} exceptionType={}",examId,job,stage,failure.getClass().getSimpleName()); }
        log.info("oral-stt job={} providerMs={} error={}",job,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),error);
        long completedAt=System.nanoTime();
        for (int attempt=0; attempt<3; attempt++) {
            try {
                oral.completeTranscription(work,text,error);
                log.info("oral-stt-complete job={} claimMs={} commitMs={} totalMs={}",job,claimMs,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-completedAt),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-claimedAt));
                return;
            }
            catch (AppException conflict) {
                if (conflict.getErrorCode().getCode()!=1303 || attempt==2) { log.warn("Chưa chốt được job STT {}, mã {}",job,conflict.getErrorCode().getCode()); return; }
                try { Thread.sleep(150L*(attempt+1)); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
}
