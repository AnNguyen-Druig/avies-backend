package com.avies.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SubjectUpdateRequest {

    @Size(max = 20, message = "Mã môn học tối đa 20 ký tự")
    String code;

    @Size(max = 255, message = "Tên môn học tối đa 255 ký tự")
    String name;

    String description;
}
