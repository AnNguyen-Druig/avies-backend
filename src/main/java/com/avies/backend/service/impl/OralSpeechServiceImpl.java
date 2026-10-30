package com.avies.backend.service.impl;

import com.avies.backend.config.OralSpeechProperties;
import com.avies.backend.dto.request.*;
import com.avies.backend.exception.*;
import com.avies.backend.service.*;
import com.avies.backend.service.support.ExamAccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Gọi TTS ngoài transaction; cache hữu hạn và gộp yêu cầu cùng lượt để nghe lại không gọi AI lần nữa. */
@Service
@Validated
@lombok.extern.slf4j.Slf4j
public class OralSpeechServiceImpl implements OralSpeechService {
    /** Biên điều phối có transaction ngắn, không trả prompt cho controller. */ private final OralExamService oral;
    /** Nhà cung cấp có thể giả lập bằng HTTP stub trong test. */ private final OralSpeechProvider provider;
    /** Kiểm tra vai trò DB cả khi nghe mẫu. */ private final ExamAccessPolicy access;
    /** Cấu hình riêng, không đọc key Feature 1. */ private final OralSpeechProperties settings;
    /** Đồng hồ cache thay được trong kiểm thử. */ private final Clock clock;
    /** Tối đa hai lần tổng hợp đồng thời, không xếp hàng vô hạn. */ private final Semaphore capacity = new Semaphore(2);
    /** Audio chỉ trong RAM, không lưu thư mục public hoặc cache HTTP dùng chung. */ private final LinkedHashMap<Object, Cached> cache = new LinkedHashMap<>();

    /** DI theo các service hiện có; không tự mở database hoặc gọi provider khi khởi động BE. */
    public OralSpeechServiceImpl(OralExamService oral, OralSpeechProvider provider, ExamAccessPolicy access, OralSpeechProperties settings, Clock clock) {
        this.oral = oral; this.provider = provider; this.access = access; this.settings = settings; this.clock = clock;
    }
    /** Làm nóng cache không mở turn hoặc cấp quyền cho Student. */
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void prefetch(com.avies.backend.service.support.OralSpeechPlan plan) {
        if (settings.ready()) audio(plan.turnId(), () -> provider.synthesize(plan.text(),plan.language()));
    }
    /** Kiểm tra quyền cả trước và sau gọi mạng, kể cả khi cache đã có audio. */
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public byte[] current(Long examId, OralSpeechRequest request) {
        var plan = oral.speechPlan(examId, request); ready();
        byte[] wav = audio(plan.turnId(), () -> provider.synthesize(plan.text(), plan.language()));
        oral.confirmSpeech(examId, request, plan.language()); return wav;
    }
    /** Preview không mở exam và chỉ được đọc hai câu mẫu cố định. */
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public byte[] preview(OralSpeechPreviewRequest request) {
        access.currentUser("STUDENT"); ready();
        String text = "vi".equals(request.language()) ? "Câu số 1. Xin chào. Đây là giọng đọc thử cho phòng thi vấn đáp."
                : "Question number 1. Hello. This is a voice check for your oral examination.";
        return audio("preview-" + request.language(), () -> provider.synthesize(text, request.language()));
    }
    /** Không gọi mạng nếu người quản lý chưa chủ động bật TTS. */
    private void ready() { if (!settings.ready()) throw new AppException(ErrorCode.ORAL_SPEECH_NOT_READY); }
    /** Một future cho mỗi turn; lỗi không được cache như audio thành công. */
    private byte[] audio(Object key, Supplier<byte[]> create) {
        Cached item; boolean owner = false;
        synchronized (cache) {
            cache.entrySet().removeIf(e -> e.getValue().result.isDone() && e.getValue().expires.isBefore(clock.instant()));
            item = cache.get(key);
            if (item == null) {
                trim(0);
                if (cache.size() >= 16 || !capacity.tryAcquire()) throw new AppException(ErrorCode.ORAL_SPEECH_QUOTA);
                item = new Cached(clock.instant().plusSeconds(1200)); cache.put(key, item); owner = true;
            }
        }
        if (owner) {
            long started=System.nanoTime(); boolean success=false;
            try {
                byte[] wav = create.get(); GeminiOralSpeechProvider.validateWav(wav);
                synchronized (cache) { trim(wav.length); item.size = wav.length; }
                item.result.complete(wav);
                success=true;
            } catch (RuntimeException failure) {
                synchronized (cache) { cache.remove(key, item); }
                item.result.completeExceptionally(failure);
            } finally { capacity.release(); log.info("oral-tts turn={} providerMs={} success={}",key,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),success); }
        }
        try { return item.result.get(40, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); }
        catch (ExecutionException failed) { if (failed.getCause() instanceof AppException safe) throw safe; throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); }
        catch (TimeoutException timeout) { throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); }
    }
    /** Tối đa 16 entries/32 MiB; không đẩy công việc đang chạy ra cache để tránh gọi trùng. */
    private void trim(int incoming) {
        long bytes = cache.values().stream().mapToLong(c -> c.size).sum();
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext() && (cache.size() >= 16 || bytes + incoming > 32L * 1024 * 1024)) {
            var prior = iterator.next().getValue();
            if (prior.result.isDone()) { bytes -= prior.size; iterator.remove(); }
        }
    }
    /** Future chứa byte WAV, không chứa đề/key hoặc dữ liệu Student. */
    private static final class Cached {
        /** Chia sẻ kết quả cho các yêu cầu trùng. */ private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        /** Cache ngắn hạn, không phải bằng chứng lịch sử bài thi. */ private final Instant expires;
        /** Số byte để giới hạn RAM. */ private int size;
        /** Tạo entry trước khi gọi mạng. */ private Cached(Instant expires) { this.expires = expires; }
    }
}
