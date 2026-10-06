package com.avies.backend.service;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

/**
 * Hợp đồng CRUD câu hỏi, không bao gồm thao tác duyệt hoặc tích hợp AI.
 */
public interface QuestionService {
    /**
     * Tạo bản nháp thủ công thuộc môn học được phép quản lý.
     */
    QuestionResponse createQuestion(@Valid QuestionCreationRequest request);

    /**
     * Đọc một câu hỏi trong phạm vi quyền của người đăng nhập.
     */
    QuestionResponse getQuestion(@Positive Long id);

    /**
     * Tìm kiếm có phân trang và giới hạn quyền ngay tại truy vấn.
     */
    PageResponse<QuestionSummaryResponse> searchQuestions(@Valid QuestionSearchRequest request);

    /**
     * Sửa từng phần bản nháp, từ chối dữ liệu cũ hoặc câu đang được sử dụng.
     */
    QuestionResponse updateQuestion(@Positive Long id, @Valid QuestionUpdateRequest request);

    /**
     * Xóa bản nháp chưa được sử dụng; gọi lại khi đã xóa không tạo tác dụng phụ.
     */
    void deleteQuestion(@Positive Long id);
}
