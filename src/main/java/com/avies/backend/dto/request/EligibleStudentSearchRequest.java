package com.avies.backend.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Tìm sinh viên theo username hoặc họ tên, không suy diễn quan hệ đăng ký môn chưa tồn tại. */
@Getter
@Setter
public class EligibleStudentSearchRequest extends ExamAssignmentPageRequest {
    /** Từ khóa literal không phân biệt hoa thường; tối đa 100 ký tự, không tìm theo email. */
    @Size(max = 100)
    private String query;
}
