package com.avies.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

/** Danh sách môn cần bổ sung hoặc thu hồi của một giảng viên. */
@Getter
@Setter
public class LecturerSubjectAssignmentRequest {
    /** Tài khoản đích phải tồn tại và đang có vai trò LECTURER. */
    @NotNull
    @Positive
    private Long lecturerId;

    /** Tối đa 100 mã môn mỗi yêu cầu; mã trùng được xử lý một lần. */
    @NotEmpty
    @Size(max = 100)
    private List<@NotNull @Positive Long> subjectIds;
}
