package com.avies.backend.service;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Phân công và lịch cơ bản, không chọn câu, bắt đầu thi hoặc chấm điểm. */
public interface StudentExamService {
    /** Lecturer tìm user hiện là STUDENT, không có bộ lọc enrollment chưa tồn tại. */
    PageResponse<EligibleStudentResponse> eligible(@NotNull @Valid EligibleStudentSearchRequest request);
    /** Gán batch nguyên tử; gộp ID trùng và giữ nguyên bài đã có. */
    StudentAssignmentResponse assign(@NotNull @Positive Long sessionId, @NotNull @Valid StudentAssignmentRequest request);
    /** Chủ sở hữu hoặc ADMIN đọc lịch đã gán, kể cả sau thu hồi môn. */
    PageResponse<StudentExamAssignmentResponse> assigned(@NotNull @Positive Long sessionId, @NotNull @Valid ExamAssignmentPageRequest request);
    /** Chỉ chủ sở hữu còn phân công được xóa bài ASSIGNED chưa có bất kỳ dữ liệu thi nào. */
    void remove(@NotNull @Positive Long id);
    /** Chỉ trả lịch của sinh viên được nhận diện từ authentication. */
    PageResponse<MyStudentExamResponse> mine(@NotNull @Valid ExamAssignmentPageRequest request);
}
