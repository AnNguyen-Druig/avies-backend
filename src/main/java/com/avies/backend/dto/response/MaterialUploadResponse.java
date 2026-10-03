package com.avies.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MaterialUploadResponse {
    Long id;
    Long subjectId;
    String subjectCode;
    String subjectName;
    String title;
    String fileName;
    String storagePath;
    String mimeType;
    Integer totalChunks;
    String processingStatus;
    String uploadedBy;
    OffsetDateTime createdAt;
}
