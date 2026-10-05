package com.avies.backend.service;

import org.springframework.web.multipart.MultipartFile;

public interface DocumentParserService {

    /**
     * Bóc tách toàn bộ nội dung text từ file tài liệu (PDF, Word, PPTX, TXT...) sử dụng Apache Tika.
     */
    String extractText(MultipartFile file);
}
