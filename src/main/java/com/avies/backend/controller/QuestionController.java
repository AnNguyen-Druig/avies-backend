package com.avies.backend.controller;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.*;
import com.avies.backend.service.QuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * API CRUD câu hỏi dành cho Admin và Giảng viên.
 */
@Tag(name = "Questions", description = "Ngân hàng câu hỏi vấn đáp: tạo thủ công, sinh tự động AI, tìm kiếm, cập nhật, xóa")
@RestController
@RequestMapping({"/questions"})
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER')")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class QuestionController {

    QuestionService questionService;

    @Operation(summary = "Sinh câu hỏi AI tự động (RAG)",
            description = "Dùng RAG pipeline: Embedding keyword -> Tìm tài liệu liên quan qua pgvector -> GPT-4o/Gemini sinh câu hỏi")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Sinh thành công, trả về danh sách câu hỏi"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Yêu cầu không hợp lệ"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Dịch vụ AI không khả dụng")
    })
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<List<QuestionResponse>>> generate(
            @Valid @RequestBody QuestionGenerateRequest request) {
        List<QuestionResponse> questions = questionService.generateQuestions(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<List<QuestionResponse>>builder()
                        .message("Sinh câu hỏi thành công.")
                        .result(questions)
                        .build());
    }

    @Operation(summary = "Tạo câu hỏi thủ công", description = "Giảng viên hoặc Admin tạo câu hỏi vấn đáp thủ công")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Tạo thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<QuestionResponse>> create(
            @Valid @RequestBody QuestionCreationRequest request) {
        QuestionResponse question = questionService.createQuestion(request);
        return ResponseEntity.created(URI.create("/questions/" + question.getId()))
                .body(ApiResponse.<QuestionResponse>builder().result(question).build());
    }

    @Operation(summary = "Tìm kiếm câu hỏi", description = "Lọc và phân trang câu hỏi theo tiêu chí: môn học, bloom level, trạng thái, nguồn...")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Kết quả tìm kiếm")
    })
    @GetMapping
    public ApiResponse<PageResponse<QuestionSummaryResponse>> search(
            @Valid @ModelAttribute QuestionSearchRequest request) {
        return ApiResponse.<PageResponse<QuestionSummaryResponse>>builder()
                .result(questionService.searchQuestions(request)).build();
    }

    @Operation(summary = "Lấy chi tiết câu hỏi", description = "Trả về đầy đủ thông tin câu hỏi bao gồm rubric")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Thông tin câu hỏi"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy câu hỏi")
    })
    @GetMapping("/{id}")
    public ApiResponse<QuestionResponse> get(
            @Parameter(description = "ID của câu hỏi") @PathVariable("id") @Positive Long id) {
        return ApiResponse.<QuestionResponse>builder().result(questionService.getQuestion(id)).build();
    }

    @Operation(summary = "Cập nhật câu hỏi", description = "Cập nhật một phần hoặc toàn bộ câu hỏi (PATCH)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cập nhật thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy câu hỏi")
    })
    @PatchMapping("/{id}")
    public ApiResponse<QuestionResponse> update(
            @Parameter(description = "ID của câu hỏi") @PathVariable("id") @Positive Long id,
            @Valid @RequestBody QuestionUpdateRequest request) {
        return ApiResponse.<QuestionResponse>builder().result(questionService.updateQuestion(id, request)).build();
    }

    @Operation(summary = "Phê duyệt câu hỏi", description = "Phê duyệt câu hỏi vào ngân hàng chính thức (chuyển trạng thái sang APPROVED)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Phê duyệt thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Chưa xác thực"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Không có quyền phê duyệt"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy câu hỏi"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Câu hỏi đã bị lưu trữ")
    })
    @RequestMapping(value = "/{id}/approve", method = {RequestMethod.POST, RequestMethod.PATCH})
    public ApiResponse<QuestionResponse> approve(
            @Parameter(description = "ID của câu hỏi") @PathVariable("id") @Positive Long id) {
        return ApiResponse.<QuestionResponse>builder()
                .message("Phê duyệt câu hỏi thành công.")
                .result(questionService.approveQuestion(id))
                .build();
    }

    @Operation(summary = "Xóa câu hỏi", description = "Xóa bản nháp chưa sử dụng. Yêu cầu lặp lại vẫn thành công nếu tài nguyên đã mất.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Xóa thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Câu hỏi đã được sử dụng trong bài thi, không thể xóa")
    })
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @Parameter(description = "ID của câu hỏi") @PathVariable("id") @Positive Long id) {
        questionService.deleteQuestion(id);
        return ApiResponse.<Void>builder().message("Câu hỏi không còn tồn tại.").build();
    }
}