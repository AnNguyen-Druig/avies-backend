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
 * API CRUD cau hoi danh cho Admin va Giang vien.
 */
@Tag(name = "Questions", description = "Ngan hang cau hoi van dap: tao thu cong, sinh tu dong AI, tim kiem, cap nhat, xoa")
@RestController
@RequestMapping({"/questions"})
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_LECTURER')")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class QuestionController {

    QuestionService questionService;

    @Operation(summary = "Sinh cau hoi AI tu dong (RAG)",
            description = "Dung RAG pipeline: Embedding keyword -> Tim tai lieu lien quan qua pgvector -> GPT-4o/Gemini sinh cau hoi + rubric")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Sinh thanh cong, tra ve danh sach cau hoi"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request khong hop le"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI service khong kha dung")
    })
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<List<QuestionResponse>>> generate(
            @Valid @RequestBody QuestionGenerateRequest request) {
        List<QuestionResponse> questions = questionService.generateQuestions(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<List<QuestionResponse>>builder()
                        .message("Sinh cau hoi va rubric thanh cong.")
                        .result(questions)
                        .build());
    }

    @Operation(summary = "Tao cau hoi thu cong", description = "Giang vien hoac Admin tao cau hoi van dap thu cong")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Tao thanh cong"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Du lieu khong hop le")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<QuestionResponse>> create(
            @Valid @RequestBody QuestionCreationRequest request) {
        QuestionResponse question = questionService.createQuestion(request);
        return ResponseEntity.created(URI.create("/questions/" + question.getId()))
                .body(ApiResponse.<QuestionResponse>builder().result(question).build());
    }

    @Operation(summary = "Tim kiem cau hoi", description = "Loc va phan trang cau hoi theo tieu chi: mon hoc, bloom level, trang thai, nguon...")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ket qua tim kiem")
    })
    @GetMapping
    public ApiResponse<PageResponse<QuestionSummaryResponse>> search(
            @Valid @ModelAttribute QuestionSearchRequest request) {
        return ApiResponse.<PageResponse<QuestionSummaryResponse>>builder()
                .result(questionService.searchQuestions(request)).build();
    }

    @Operation(summary = "Lay chi tiet cau hoi", description = "Tra ve day du thong tin cau hoi bao gom rubric")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Thong tin cau hoi"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Khong tim thay cau hoi")
    })
    @GetMapping("/{id}")
    public ApiResponse<QuestionResponse> get(
            @Parameter(description = "ID cua cau hoi") @PathVariable("id") @Positive Long id) {
        return ApiResponse.<QuestionResponse>builder().result(questionService.getQuestion(id)).build();
    }

    @Operation(summary = "Cap nhat cau hoi", description = "Cap nhat mot phan hoac toan bo cau hoi (PATCH)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cap nhat thanh cong"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Khong tim thay cau hoi")
    })
    @PatchMapping("/{id}")
    public ApiResponse<QuestionResponse> update(
            @Parameter(description = "ID cua cau hoi") @PathVariable("id") @Positive Long id,
            @Valid @RequestBody QuestionUpdateRequest request) {
        return ApiResponse.<QuestionResponse>builder().result(questionService.updateQuestion(id, request)).build();
    }

    @Operation(summary = "Xoa cau hoi", description = "Xoa ban nhap chua su dung. Yeu cau lap lai van thanh cong neu tai nguyen da mat.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Xoa thanh cong"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Cau hoi da duoc su dung trong bai thi, khong the xoa")
    })
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @Parameter(description = "ID cua cau hoi") @PathVariable("id") @Positive Long id) {
        questionService.deleteQuestion(id);
        return ApiResponse.<Void>builder().message("Cau hoi khong con ton tai.").build();
    }
}