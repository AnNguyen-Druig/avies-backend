package com.avies.backend.controller;

import com.avies.backend.dto.request.*;
import com.avies.backend.service.OralSpeechService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Chỉ trả audio lượt hiện tại; controller không đọc repository hoặc nhận văn bản từ Student. */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('ROLE_STUDENT')")
@Tag(name = "Oral speech", description = "Giọng đọc Việt/Anh cho đúng lượt snapshot hiện tại")
public class OralSpeechController {
    /** Service xác thực chủ lượt và lease, rồi tổng hợp audio ngoài transaction. */ private final OralSpeechService service;
    /** POST vì có thể gọi TTS và xác nhận PREPARING → SPEAKING. */
    @PostMapping("/student-exams/{id}/oral-examination/speech")
    @Operation(summary = "Nghe câu hỏi hiện tại", description = "Trả WAV riêng tư; cần clientSessionId, turnId và expectedRevision. Không nhận nội dung đề.")
    public ResponseEntity<byte[]> current(@PathVariable @Positive Long id, @Valid @RequestBody OralSpeechRequest request) {
        return audio(service.current(id, request));
    }
    /** NDJSON audio chunks; chỉ end sau confirmSpeech. EOF/lỗi giữa stream không xác nhận đã nghe xong. */
    @PostMapping("/student-exams/{id}/oral-examination/speech-stream")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> stream(
            @PathVariable @Positive Long id, @Valid @RequestBody OralSpeechRequest request) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext(); context.setAuthentication(authentication);
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = output -> {
            var json = new tools.jackson.databind.ObjectMapper();
            java.util.function.Consumer<Object> send = value -> {
                try { output.write((json.writeValueAsString(value) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.flush(); }
                catch (java.io.IOException disconnected) { throw new java.io.UncheckedIOException(disconnected); }
            };
            new org.springframework.security.concurrent.DelegatingSecurityContextRunnable(() -> {
                long started = System.nanoTime(); var first = new java.util.concurrent.atomic.AtomicBoolean(true);
                try {
                    service.currentStream(id, request, pcm -> {
                        if (first.getAndSet(false)) org.slf4j.LoggerFactory.getLogger(getClass()).info("oral-tts-stream exam={} turn={} firstAudioMs={}",
                                id, request.turnId(), java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                        send.accept(java.util.Map.of("type", "audio", "data", java.util.Base64.getEncoder().encodeToString(pcm)));
                    });
                    send.accept(java.util.Map.of("type", "end"));
                } catch (java.io.UncheckedIOException disconnected) { /* Không confirm khi consumer đã ngắt giữa chừng. */ }
                catch (RuntimeException failure) {
                    int code = failure instanceof com.avies.backend.exception.AppException safe ? safe.getErrorCode().getCode() : 1808;
                    try { send.accept(java.util.Map.of("type", "error", "code", code)); } catch (RuntimeException disconnected) { }
                }
            }, context).run();
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/x-ndjson"))
                .cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no").header("X-Content-Type-Options", "nosniff").body(body);
    }
    /** Kiểm tra giọng Việt/Anh bằng câu cố định, không thay đổi tiến trình thi. */
    @PostMapping("/oral-examination/speech-preview")
    @Operation(summary = "Nghe thử giọng đọc bằng mẫu cố định", description = "Chỉ nhận language=vi hoặc en; không mở hoặc ghi kết quả thi.")
    public ResponseEntity<byte[]> preview(@Valid @RequestBody OralSpeechPreviewRequest request) { return audio(service.preview(request)); }
    /** Audio đề không được cache bởi proxy hoặc trình duyệt dùng chung. */
    private ResponseEntity<byte[]> audio(byte[] wav) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(wav);
    }
}
