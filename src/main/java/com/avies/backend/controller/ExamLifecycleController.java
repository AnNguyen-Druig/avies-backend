package com.avies.backend.controller;

import com.avies.backend.dto.request.ExamLifecycleRequest;
import com.avies.backend.dto.response.*;
import com.avies.backend.service.ExamLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Lệnh vòng đời thi; controller chỉ gọi service, không tự quyết định quyền hoặc trạng thái. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Exam lifecycle", description = "Bắt đầu, hoàn thành lượt thi và đóng phiên theo lịch máy chủ")
public class ExamLifecycleController {
    /** Service giữ toàn bộ business rules và transaction. */
    private final ExamLifecycleService service;

    /** Start lặp hợp lệ trả cùng đề đã chốt, không làm lộ đáp án hoặc rubric cho sinh viên. */
    @PostMapping("/student-exams/{id}/starts")
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    @Operation(summary = "Start Exam — sinh viên bắt đầu lượt của mình", description = "Chỉ trong slot, vào muộn không được cộng giờ. Chọn câu/snapshot và đổi IN_PROGRESS/ONGOING nguyên tử. Body rỗng hoặc {}.")
    public ApiResponse<StartedExamResponse> start(@PathVariable @Positive Long id,
            @Valid @RequestBody(required = false) ExamLifecycleRequest request) {
        return ApiResponse.<StartedExamResponse>builder().result(service.start(id)).build();
    }

    /** Finish không nhận câu trả lời, audio hoặc điểm; Feature 3 phải lưu câu trả lời trước. */
    @PostMapping("/student-exams/{id}/completions")
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    @Operation(summary = "Finish Exam — xác nhận hoàn thành lượt thi", description = "Chỉ trong slot, cần đủ câu trả lời đã lưu bởi Feature 3. Không chấm điểm AI. Gọi lại lượt COMPLETED giữ nguyên completedAt. Body rỗng hoặc {}.")
    public ApiResponse<ExamAttemptResponse> finish(@PathVariable @Positive Long id,
            @Valid @RequestBody(required = false) ExamLifecycleRequest request) {
        return ApiResponse.<ExamAttemptResponse>builder().result(service.finish(id)).build();
    }

    /** Đóng không xóa dữ liệu hoặc chấm điểm giả cho sinh viên vắng/hết giờ. */
    @PostMapping("/exam-sessions/{id}/closures")
    @PreAuthorize("hasAuthority('ROLE_LECTURER')")
    @Operation(summary = "Close Session — chủ phiên đóng phiên đã hết giờ", description = "Cần còn phân công môn. ASSIGNED→ABSENT; IN_PROGRESS→EXPIRED; giữ COMPLETED và toàn bộ bằng chứng. Phiên→COMPLETED. Body rỗng hoặc {}.")
    public ApiResponse<ClosedExamSessionResponse> close(@PathVariable @Positive Long id,
            @Valid @RequestBody(required = false) ExamLifecycleRequest request) {
        return ApiResponse.<ClosedExamSessionResponse>builder().result(service.close(id)).build();
    }
}
