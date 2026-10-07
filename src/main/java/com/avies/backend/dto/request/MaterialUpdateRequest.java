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
public class MaterialUpdateRequest {

    @NotBlank(message = "Tiêu đề tài liệu không được để trống")
    @Size(max = 255, message = "Tiêu đề tài liệu tối đa 255 ký tự")
    String title;
}
