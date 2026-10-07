package com.avies.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserCreateRequest {

    @NotBlank(message = "Username không được để trống")
    @Size(min = 3, max = 50, message = "Username phải từ 3 đến 50 ký tự")
    String username;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không hợp lệ")
    String email;

    // Tùy chọn: nếu để trống hệ thống sẽ tự sinh ngẫu nhiên 8 ký tự
    String password;

    @NotBlank(message = "Họ và tên không được để trống")
    String fullName;

    // STUDENT hoặc LECTURER
    @NotBlank(message = "Vai trò (roleCode) không được để trống")
    String roleCode;
}
