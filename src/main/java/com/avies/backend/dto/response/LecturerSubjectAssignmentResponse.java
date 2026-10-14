package com.avies.backend.dto.response;

import lombok.Builder;
import lombok.Getter;

/** Một phân công cùng thông tin tối thiểu để admin hiển thị danh sách. */
@Getter
@Builder
public class LecturerSubjectAssignmentResponse {
    /** Định danh bản phân công. */
    private final Long id;
    /** Định danh giảng viên được phân công. */
    private final Long lecturerId;
    /** Tên đăng nhập giúp phân biệt giảng viên trùng tên. */
    private final String lecturerUsername;
    /** Họ tên hiển thị của giảng viên. */
    private final String lecturerFullName;
    /** Thông tin môn học được phân công, không trả entity trực tiếp. */
    private final SubjectResponse subject;
}
