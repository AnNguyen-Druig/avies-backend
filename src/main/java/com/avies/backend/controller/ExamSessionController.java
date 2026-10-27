package com.avies.backend.controller;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.*;
import com.avies.backend.service.ExamSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.avies.backend.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * Năm API phiên thi; controller chỉ gọi service, không truy cập repository hoặc trả entity.
 */
@RestController
@RequestMapping("/exam-sessions")
@RequiredArgsConstructor
@Tag(name = "Exam sessions", description = "Quản lý phiên thi; ADMIN chỉ đọc, LECTURER ghi phiên thuộc quyền")
@ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request, ID, lịch hoặc phân trang không hợp lệ"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Chưa xác thực hoặc tài khoản không còn tồn tại"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Sai quyền hiện hành hoặc đã thu hồi môn khi ghi"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy môn/phiên hoặc phiên ngoài phạm vi sở hữu"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Phiên sai trạng thái, đã có bài thi hoặc gặp xung đột khóa")
})
public class ExamSessionController {

    private final ExamSessionService service;

    /**
     * Trả 201 và Location sau khi chủ sở hữu được lấy từ tài khoản xác thực hiện hành.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_LECTURER')")
    @Operation(summary = "Giảng viên tạo phiên thi", description = "Cần phân công môn hiện hành, lịch tương lai, rubric môn đầy đủ/hợp lệ và ít nhất maxMainQuestions câu APPROVED đủ điều kiện Start trong môn. Thiếu rubric trả 400/1305, thiếu câu trả 400/1306 kèm số tối thiểu. Chưa chọn đề/chốt snapshot lúc tạo và chưa cần sinh viên. Không nhận lecturerId/status.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Tạo phiên UPCOMING thành công; Location trỏ tới API chi tiết")
    public ResponseEntity<ApiResponse<ExamSessionResponse>> create(
            @Valid @RequestBody ExamSessionCreationRequest request) {
        ExamSessionResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/exam-sessions/" + response.getId()))
                .body(ApiResponse.<ExamSessionResponse>builder().result(response).build());
    }

    /**
     * Danh sách và tổng số bản ghi đều nằm trong phạm vi quyền của người đăng nhập.
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_LECTURER', 'ROLE_ADMIN')")
    @Operation(summary = "Tìm phiên thi có phân trang", description = "Lecturer chỉ xem phiên của mình, kể cả sau thu hồi môn; admin xem toàn bộ. page=0..1000000, size=1..100 (mặc định 20); ID giảm dần.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ApiResponse<PageResponse<ExamSessionResponse>>, items có thể rỗng")
    public ApiResponse<PageResponse<ExamSessionResponse>> search(
            @Valid @ModelAttribute ExamSessionSearchRequest request) {
        return ApiResponse.<PageResponse<ExamSessionResponse>>builder()
                .result(service.search(request)).build();
    }

    /**
     * Đọc lịch sử sở hữu không phụ thuộc phân công; phiên người khác được che bằng 404.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_LECTURER', 'ROLE_ADMIN')")
    @Operation(summary = "Xem chi tiết phiên thi", description = "Lecturer xem phiên sở hữu, admin xem mọi phiên; không trả dữ liệu sinh viên hoặc điểm.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Chi tiết phiên thi")
    public ApiResponse<ExamSessionResponse> get(
            @Parameter(description = "ID phiên thi dương") @PathVariable @Positive Long id) {
        return ApiResponse.<ExamSessionResponse>builder().result(service.get(id)).build();
    }

    /**
     * PATCH giữ trường bỏ qua; null tường minh và trạng thái cuối không hợp lệ bị từ chối.
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_LECTURER')")
    @Operation(summary = "Chủ sở hữu cập nhật phiên UPCOMING", description = "Phải còn phân công môn và chưa có StudentExam bắt đầu. Gộp PATCH rồi kiểm tra lịch tương lai; nếu đổi maxMainQuestions thì kiểm tra lại rubric và ngân hàng như New Exam (400/1305 hoặc 1306 khi thiếu). Không đổi môn/chủ sở hữu/trạng thái.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Phiên sau cập nhật, kể cả yêu cầu giữ nguyên giá trị")
    public ApiResponse<ExamSessionResponse> update(
            @PathVariable @Positive Long id, @Valid @RequestBody ExamSessionUpdateRequest request) {
        return ApiResponse.<ExamSessionResponse>builder().result(service.update(id, request)).build();
    }

    /**
     * Xóa trả envelope 200; ID đã mất hoặc ngoài quyền trả cùng lỗi 404.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_LECTURER')")
    @Operation(summary = "Chủ sở hữu xóa phiên chưa sử dụng", description = "Chỉ UPCOMING, còn phân công, không có bất kỳ StudentExam nào. Không cascade xóa lịch sử. Gọi lại ID đã xóa trả 404.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Đã xóa phiên chưa sử dụng, không có result")
    public ApiResponse<Void> delete(@PathVariable @Positive Long id) {
        service.delete(id);
        return ApiResponse.<Void>builder().message("Đã xóa phiên thi chưa sử dụng.").build();
    }
}
