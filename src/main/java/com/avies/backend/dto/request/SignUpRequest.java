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
public class SignUpRequest {

    @NotBlank(message = "Username không được để trống")
    @Size(min = 3, max = 50, message = "Request không hợp lệ")
    String username;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không hợp lệ")
    String email;

    @NotBlank(message = "Mật khẩu không được để trống")
    @Size(min = 6, message = "Mật khẩu quá ngắn")
    String password;

    @NotBlank(message = "Fullname không được để trống")
    String fullName;

    // Optional: STUDENT, LECTURER, ADMIN (mac dinh: STUDENT)
    String roleCode;
}
