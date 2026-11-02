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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Gọi TTS ngoài transaction; cache hữu hạn và gộp cùng lời đọc để tránh tổng hợp lại audio giống nhau. */
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
        if (settings.ready()) audio(cacheKey(plan.text(),plan.language()), () -> provider.synthesize(plan.text(),plan.language()));
    }
    /** Kiểm tra quyền cả trước và sau gọi mạng, kể cả khi cache đã có audio. */
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public byte[] current(Long examId, OralSpeechRequest request) {
        long started=System.nanoTime();
        var plan = oral.speechPlan(examId, request); ready();
        long planned=System.nanoTime();
        byte[] wav = audio(cacheKey(plan.text(),plan.language()), () -> provider.synthesize(plan.text(), plan.language()));
        long synthesized=System.nanoTime();
        oral.confirmSpeech(examId, request, plan.language());
        log.info("oral-speech exam={} turn={} planMs={} audioWaitMs={} confirmMs={} totalMs={}",examId,plan.turnId(),
                TimeUnit.NANOSECONDS.toMillis(planned-started),TimeUnit.NANOSECONDS.toMillis(synthesized-planned),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-synthesized),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        return wav;
    }
    /** Preview không mở exam và chỉ được đọc hai câu mẫu cố định. */
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public byte[] preview(OralSpeechPreviewRequest request) {
        access.currentUser("STUDENT"); ready();
        String text = "vi".equals(request.language()) ? "Câu số 1. Xin chào. Đây là giọng đọc thử cho phòng thi vấn đáp."
                : "Question number 1. Hello. This is a voice check for your oral examination.";
        return audio(cacheKey(text,request.language()), () -> provider.synthesize(text, request.language()));
    }
    /** Cùng lời đọc/ngôn ngữ/model/voice dùng lại WAV; số câu nằm trong text nên không bị đọc sai thứ tự. */
    private String cacheKey(String text, String language) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for (var value: List.of(Objects.toString(settings.getModel(),""),Objects.toString(settings.getVoice(),""),language,text)) {
                digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte)0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Không gọi mạng nếu người quản lý chưa chủ động bật TTS. */
    private void ready() { if (!settings.ready()) throw new AppException(ErrorCode.ORAL_SPEECH_NOT_READY); }
    /** Một future cho mỗi lời đọc; quota giữ lỗi 30 giây để warmup/click nghe không gọi lại liên tục. */
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
                synchronized (cache) {
                    if (failure instanceof AppException safe && safe.getErrorCode()==ErrorCode.ORAL_SPEECH_QUOTA)
                        item.expires=clock.instant().plusSeconds(30);
                    else cache.remove(key, item);
                }
                item.result.completeExceptionally(failure);
            } finally { capacity.release(); log.info("oral-tts cache={} providerMs={} success={}",key,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),success); }
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
        /** Cache ngắn hạn; quota chỉ giữ lỗi 30 giây, không được coi là WAV thành công. */ private Instant expires;
        /** Số byte để giới hạn RAM. */ private int size;
        /** Tạo entry trước khi gọi mạng. */ private Cached(Instant expires) { this.expires = expires; }
    }
}
