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
