package com.avies.backend.service;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Hợp đồng quản lý phiên thi; chưa gán sinh viên, chọn câu hoặc chuyển trạng thái bắt đầu thi. */
public interface ExamSessionService {
    /** Giảng viên tạo phiên UPCOMING trong môn đang được phân công. */
    ExamSessionResponse create(@NotNull @Valid ExamSessionCreationRequest request);

    /** Lecturer chỉ tìm phiên của mình; admin theo dõi toàn bộ. */
    PageResponse<ExamSessionResponse> search(@NotNull @Valid ExamSessionSearchRequest request);

    /** Đọc phiên được phép xem, kể cả sau thu hồi phân công. */
    ExamSessionResponse get(@NotNull @Positive Long id);

    /** Cập nhật các trường được gửi sau khi khóa và kiểm tra trạng thái hiện hành. */
    ExamSessionResponse update(@NotNull @Positive Long id, @NotNull @Valid ExamSessionUpdateRequest request);

    /** Chỉ xóa phiên UPCOMING của chủ sở hữu còn phân công và chưa có bất kỳ StudentExam nào. */
    void delete(@NotNull @Positive Long id);
}
