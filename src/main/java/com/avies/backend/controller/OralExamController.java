package com.avies.backend.controller;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.*;
import com.avies.backend.service.OralExamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** API Student chỉ gọi service, không truy cập repository hoặc nhận callback giả từ client. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/student-exams/{id}/oral-examination")
@PreAuthorize("hasAuthority('ROLE_STUDENT')")
@Tag(name = "Oral examination", description = "Tiến trình, thời gian, quyền tab và lệnh Student; STT/AI xử lý qua worker nội bộ")
public class OralExamController {
    /** Service kiểm tra chủ lượt và đồng hồ máy chủ. */ private final OralExamService service;

    /** Mở cùng lượt thi đã snapshot, không tạo lại đề khi gọi lặp. */
    @PostMapping
    @Operation(summary = "Mở tiến trình thi vấn đáp", description = "Dùng clientSessionId ổn định cho tab. Chỉ mở lần đầu trong slot; callback dịch vụ chưa có endpoint public.")
    public ApiResponse<OralExamResponse> open(@PathVariable @Positive Long id, @Valid @RequestBody OralExamStartRequest request) {
        return ApiResponse.<OralExamResponse>builder().result(service.open(id, request)).build();
    }

    /** Đồng bộ tiến trình và timeout; không trả danh sách nội dung đề hoặc rubric. */
    @GetMapping
    @Operation(summary = "Xem tiến trình và transcript của chính mình")
    public ApiResponse<OralExamResponse> get(@PathVariable @Positive Long id) {
        return ApiResponse.<OralExamResponse>builder().result(service.get(id)).build();
    }

    /** Client không tự chuyển sang câu tùy ý, ghi transcript hoặc cấp thêm follow-up. */
    @PostMapping("/actions")
    @Operation(summary = "Thao tác hội thoại có phiên bản và chống gửi trùng", description = "SUBMIT dành mã xử lý; upload audio qua endpoint submissions/{job}/audio. STT/AI chỉ được hoàn tất qua worker nội bộ.")
    public ApiResponse<OralExamResponse> execute(@PathVariable @Positive Long id, @Valid @RequestBody OralExamCommandRequest request) {
        return ApiResponse.<OralExamResponse>builder().result(service.execute(id, request)).build();
    }
    /** Heartbeat độc lập polling/audio; không mở bài thi hoặc chuyển bước. */
    @PostMapping("/lease")
    public com.avies.backend.dto.response.ApiResponse<com.avies.backend.dto.response.OralLeaseResponse> lease(@PathVariable @Positive Long id,
            @Valid @RequestBody OralExamStartRequest request) {
        return com.avies.backend.dto.response.ApiResponse.<com.avies.backend.dto.response.OralLeaseResponse>builder().result(service.renewLease(id,request)).build();
    }
}
