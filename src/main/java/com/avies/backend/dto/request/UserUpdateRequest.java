package com.avies.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserUpdateRequest {

    @Size(min = 3, max = 50, message = "Username phải từ 3 đến 50 ký tự")
    String username;

    @Size(max = 255, message = "Tên đầy đủ tối đa 255 ký tự")
    String fullName;

    @Email(message = "Email không đúng định dạng")
    String email;

    @Size(max = 50, message = "Mã quyền tối đa 50 ký tự")
    String roleCode;
}