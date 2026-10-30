package com.avies.backend.service.support;

import com.avies.backend.service.OralSpeechService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.time.Clock;
import java.util.concurrent.RejectedExecutionException;

/** Chỉ làm nóng câu vừa chốt sau commit; thất bại không thay đổi tiến trình bài thi. */
@Component
@lombok.extern.slf4j.Slf4j
public class OralSpeechWarmupWorker {
    /** Cache và provider TTS dùng chung với yêu cầu nghe có xác thực. */ private final OralSpeechService speech;
    /** Executor không xếp hàng vô hạn. */ private final ThreadPoolTaskExecutor executor;
    /** Không bắt đầu tổng hợp sau deadline. */ private final Clock clock;
    /** Không gọi mạng khi dựng bean. */
    public OralSpeechWarmupWorker(OralSpeechService speech, @Qualifier("oralSpeechWarmupExecutor") ThreadPoolTaskExecutor executor, Clock clock) {
        this.speech=speech; this.executor=executor; this.clock=clock;
    }
    /** Không xác nhận SPEAKING hoặc bắt đầu giờ trả lời khi làm nóng cache. */
    @TransactionalEventListener
    public void ready(OralSpeechWarmup event) {
        try {
            executor.execute(() -> {
                if (!clock.instant().isBefore(event.deadline().toInstant())) return;
                try { speech.prefetch(event.plan()); }
                catch (RuntimeException failure) { log.debug("Chưa làm nóng được audio turn {}; yêu cầu nghe có thể thử lại",event.plan().turnId()); }
            });
        } catch (RejectedExecutionException full) { /* Tối ưu tùy chọn; yêu cầu nghe vẫn đi qua cache/provider bình thường. */ }
    }
}
