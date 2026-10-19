package com.avies.backend.dto.response;

import lombok.Builder;
import lombok.Getter;

/** Thông tin công khai tối thiểu để chọn sinh viên; không có email, mật khẩu hoặc điểm. */
@Getter
@Builder
public class EligibleStudentResponse {
    /** ID tài khoản sinh viên. */
    private final Long id;

    /** Tên đăng nhập hiển thị. */
    private final String username;

    /** Họ tên hiển thị. */
    private final String fullName;
}
