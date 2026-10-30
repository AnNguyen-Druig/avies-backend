package com.avies.backend.service.support;

import com.avies.backend.config.OralDecisionProperties;
import com.avies.backend.exception.AppException;
import com.avies.backend.repository.OralDecisionRepository;
import com.avies.backend.service.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.*;
import java.util.concurrent.*;

/** Phục hồi quyết định từ transcript đã lưu; không cần audio hoặc tab Student còn mở. */
@Component
@lombok.extern.slf4j.Slf4j
public class OralDecisionWorker {
    /** Flag/key AI. */ private final OralDecisionProperties settings;
    /** Chỉ tìm candidate, không sửa aggregate trực tiếp. */ private final OralDecisionRepository repository;
    /** Service giữ khóa/nonce và chốt trạng thái. */ private final OralExamService oral;
    /** Gemini thật hoặc stub trong test. */ private final OralDecisionProvider provider;
    /** Executor hữu hạn riêng, không chiếm worker STT. */ private final ThreadPoolTaskExecutor executor;
    /** Giảm công việc trùng trong cùng process; DB claim mới là nguồn quyết định. */ private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    /** Không khởi động thread/gọi mạng trong constructor. */
    public OralDecisionWorker(OralDecisionProperties settings, OralDecisionRepository repository, OralExamService oral,
            OralDecisionProvider provider, @Qualifier("oralDecisionExecutor") ThreadPoolTaskExecutor executor) {
        this.settings=settings; this.repository=repository; this.oral=oral; this.provider=provider; this.executor=executor;
    }
    /** Thiếu migration hoặc flag/key thì không lấy công việc và không làm startup lỗi. */
    @Scheduled(fixedDelay=2000) public void recover() {
        if (!settings.ready() || !repository.ready()) return;
        for (var candidate : repository.candidates()) {
            if (!enqueue(candidate.examId(),candidate.submissionId())) break;
        }
    }
    /** Đánh thức ngay sau commit; không chờ nhịp quét hoặc gọi provider trên thread HTTP. */
    @TransactionalEventListener
    public void ready(OralWorkReady event) {
        if (event.stage()==OralWorkReady.Stage.DECISION && settings.ready()) enqueue(event.examId(),event.submissionId());
    }
    /** Hàng đợi hữu hạn; scheduler sẽ phục hồi khi đầy hoặc process bị dừng. */
    private boolean enqueue(long examId, UUID job) {
        if (!active.add(job)) return true;
        try {
            executor.execute(() -> { try { process(examId,job); } finally { active.remove(job); } });
            return true;
        } catch (RejectedExecutionException full) { active.remove(job); return false; }
    }
    /** Hết quota/ngân sách thì ADVANCE theo quy tắc BE, lỗi AI thì giữ job để RETRY. */
    public void process(long examId, UUID job) {
        OralDecisionWork work;
        try { work=oral.claimDecision(examId, job); } catch (AppException busy) { if (busy.getErrorCode().getCode()!=1303) log.warn("Không nhận được job AI {}, mã {}",job,busy.getErrorCode().getCode()); return; }
        if (work == null) return;
        long started=System.nanoTime();
        OralDecisionResult result=null; Integer error=null;
        try { result=work.canFollowUp() ? Objects.requireNonNull(provider.decide(work)) : OralDecisionResult.advance(); }
        catch (AppException failure) { int code=failure.getErrorCode().getCode(); error=Set.of(1815,1816,1817).contains(code) ? code : 1816; }
        catch (RuntimeException failure) { error=1816; }
        log.info("oral-ai job={} providerMs={} error={}",job,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),error);
        for (int attempt=0; attempt<3; attempt++) {
            try { oral.completeDecision(work,result,error); return; }
            catch (AppException conflict) {
                if (conflict.getErrorCode().getCode()!=1303 || attempt==2) { log.warn("Chưa chốt được job AI {}, mã {}",job,conflict.getErrorCode().getCode()); return; }
                try { Thread.sleep(150L*(attempt+1)); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
}
