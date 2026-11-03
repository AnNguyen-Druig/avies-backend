package com.avies.backend.controller;

import com.avies.backend.service.OralExamService;
import com.avies.backend.service.support.OralProgressEvents;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SSE dùng Authorization header như REST; không đưa token vào URL và không cấp quyền điều khiển tab. */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('ROLE_STUDENT')")
public class OralExamEventsController {
    private final OralExamService oral;
    private final OralProgressEvents events;

    @GetMapping(value = "/student-exams/{id}/oral-examination/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Theo dõi thay đổi tiến độ thi qua SSE", description = "ready/progress chỉ chứa revision; lấy trạng thái chính thức bằng GET có JWT. Reconnect phải đọc lại trạng thái; không replay lịch sử sự kiện.")
    public ResponseEntity<SseEmitter> subscribe(@PathVariable @Positive Long id, @AuthenticationPrincipal Jwt jwt) {
        var state = oral.get(id); // Kiểm tra owner, role hiện hành và deadline trước khi đăng ký.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .body(events.subscribe(id, state.revision(), jwt == null ? null : jwt.getExpiresAt()));
    }

    /** Lỗi trước khi mở stream vẫn trả JSON có kiểm soát để client quay về REST. */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<com.avies.backend.dto.response.ApiResponse<Void>> unavailable(org.springframework.web.server.ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(com.avies.backend.dto.response.ApiResponse.<Void>builder().code(9999)
                        .message("Kênh tiến độ chưa sẵn sàng; vui lòng sử dụng API tiến độ hiện tại.").build());
    }
}
