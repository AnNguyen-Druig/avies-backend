package com.avies.backend.controller;

import com.avies.backend.dto.request.ExamAssignmentPageRequest;
import com.avies.backend.dto.response.*;
import com.avies.backend.service.StudentExamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Lịch thi cá nhân và thu hồi bài chưa dùng; không triển khai bắt đầu thi hoặc chấm điểm. */
@RestController
@RequestMapping("/student-exams")
@RequiredArgsConstructor
@Tag(name = "Student exams", description = "Lịch cá nhân và thu hồi bài chưa sử dụng")
public class StudentExamController {

    private final StudentExamService service;

    /** Principal quyết định sinh viên; không có tham số client để truy cập lịch người khác. */
    @GetMapping("/me")
    @PreAuthorize("hasAuthority('ROLE_STUDENT')")
    @Operation(summary = "Sinh viên xem lịch thi của mình", description = "Phân trang ID giảm dần, gồm lịch sử ASSIGNED/IN_PROGRESS/COMPLETED; không trả câu hỏi, đáp án, rubric, điểm hoặc dữ liệu sinh viên khác.")
    public ApiResponse<PageResponse<MyStudentExamResponse>> mine(@Valid @ModelAttribute ExamAssignmentPageRequest request) {
        return ApiResponse.<PageResponse<MyStudentExamResponse>>builder().result(service.mine(request)).build();
    }

    /** Thu hồi không dồn slot còn lại; gọi lại ID đã xóa trả 404 thay vì 500. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_LECTURER')")
    @Operation(summary = "Chủ sở hữu thu hồi bài ASSIGNED chưa sử dụng", description = "Cần còn phân công, phiên UPCOMING, chưa có thời gian thi thực tế, câu hỏi, điểm, audio hoặc feedback. Không xóa bằng chứng thi.")
    public ApiResponse<Void> remove(@PathVariable @Positive Long id) {
        service.remove(id);
        return ApiResponse.<Void>builder().message("Đã thu hồi bài thi chưa sử dụng; lịch sinh viên khác được giữ nguyên.").build();
    }
}
