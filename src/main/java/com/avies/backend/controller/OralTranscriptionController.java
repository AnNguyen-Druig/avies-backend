package com.avies.backend.controller;

import com.avies.backend.dto.request.OralAudioRequest;
import com.avies.backend.dto.response.*;
import com.avies.backend.service.OralTranscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;
import java.util.UUID;

/** Controller chỉ gọi service, không mở repository hoặc nhận transcript do Student gõ. */
@RestController @RequiredArgsConstructor @PreAuthorize("hasAuthority('ROLE_STUDENT')")
public class OralTranscriptionController {
    /** Biên STT/audio. */ private final OralTranscriptionService service;
    /** Metadata là JSON part, audio là file; BE chỉ xử lý đúng job đã dành. */
    @PostMapping(value="/student-exams/{id}/oral-examination/submissions/{job}/audio", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary="Gửi bản ghi cho lượt xử lý STT đã được cấp")
    public ResponseEntity<ApiResponse<OralExamResponse>> upload(@PathVariable @Positive Long id, @PathVariable UUID job,
            @Valid @RequestPart("metadata") OralAudioRequest request, @RequestPart("audio") MultipartFile audio) {
        return ResponseEntity.accepted().body(ApiResponse.<OralExamResponse>builder().result(service.upload(id, job, request, audio)).build());
    }
    /** Thử thiết bị/STT, không phải endpoint nộp câu trả lời thi. */
    @PostMapping(value="/oral-examination/transcription-preview", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String,Object>> preview(@RequestParam @Pattern(regexp="vi|en") String language, @RequestPart("audio") MultipartFile audio) {
        String text = service.preview(language, audio);
        return ApiResponse.<Map<String,Object>>builder().result(Map.of("transcript", text, "hasSpeech", !text.isBlank(), "language", language)).build();
    }
}
