package com.avies.backend.controller;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.*;
import com.avies.backend.service.QuestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * API CRUD câu hỏi dành cho admin và giảng viên; nghiệp vụ duyệt được tách riêng.
 */
@RestController
@RequestMapping("/questions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER')")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class QuestionController {

    /**
     * Dịch vụ nghiệp vụ của ngân hàng câu hỏi.
     */
    QuestionService questionService;

    /**
     * Tạo câu hỏi thủ công và trả vị trí tài nguyên vừa tạo.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<QuestionResponse>> create(@Valid @RequestBody QuestionCreationRequest request) {
        QuestionResponse question = questionService.createQuestion(request);
        return ResponseEntity.created(URI.create("/questions/" + question.getId()))
                .body(ApiResponse.<QuestionResponse>builder().result(question).build());
    }

    /**
     * Liệt kê câu hỏi theo bộ lọc, phân trang và phạm vi quyền.
     */
    @GetMapping
    public ApiResponse<PageResponse<QuestionSummaryResponse>> search(@Valid @ModelAttribute QuestionSearchRequest request) {
        return ApiResponse.<PageResponse<QuestionSummaryResponse>>builder()
                .result(questionService.searchQuestions(request)).build();
    }

    /**
     * Trả chi tiết một câu hỏi được phép xem.
     */
    @GetMapping("/{id}")
    public ApiResponse<QuestionResponse> get(@PathVariable("id") @Positive Long id) {
        return ApiResponse.<QuestionResponse>builder().result(questionService.getQuestion(id)).build();
    }

    /**
     * Cập nhật riêng các trường xuất hiện trong request.
     */
    @PatchMapping("/{id}")
    public ApiResponse<QuestionResponse> update(@PathVariable("id") @Positive Long id,
                                                @Valid @RequestBody QuestionUpdateRequest request) {
        return ApiResponse.<QuestionResponse>builder().result(questionService.updateQuestion(id, request)).build();
    }

    /**
     * Xóa bản nháp chưa sử dụng; yêu cầu lặp lại vẫn thành công khi tài nguyên đã mất.
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") @Positive Long id) {
        questionService.deleteQuestion(id);
        return ApiResponse.<Void>builder().message("Câu hỏi không còn tồn tại.").build();
    }
}
