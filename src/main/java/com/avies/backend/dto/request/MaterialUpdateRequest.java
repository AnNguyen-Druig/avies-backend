package com.avies.backend.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MaterialUpdateRequest {

    @Size(max = 255, message = "Tiêu đề tài liệu tối đa 255 ký tự")
    String title;

    @Pattern(regexp = "^(?i)(vi|en)$", message = "Ngôn ngữ tài liệu chỉ chấp nhận 'vi' hoặc 'en'")
    String language;
}

