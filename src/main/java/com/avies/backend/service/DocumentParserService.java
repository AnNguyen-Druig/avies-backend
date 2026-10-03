package com.avies.backend.service;

import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Slf4j
@Service
public class DocumentParserService {

    private final Tika tika = new Tika();

    /**
     * Bóc tách toàn bộ nội dung text từ file tài liệu (PDF, Word, PPTX, TXT...) sử dụng Apache Tika.
     */
    public String extractText(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.FILE_EMPTY);
        }

        try (InputStream inputStream = file.getInputStream()) {
            log.info("Bắt đầu parse text từ file: {}, kích thước: {} bytes, contentType: {}",
                    file.getOriginalFilename(), file.getSize(), file.getContentType());

            String rawText = tika.parseToString(inputStream);

            if (rawText == null || rawText.trim().isEmpty()) {
                log.warn("Không trích xuất được text nào từ file: {}", file.getOriginalFilename());
                throw new AppException(ErrorCode.FILE_PARSING_FAILED);
            }

            // Làm sạch text: loại bỏ ký tự null byte (\u0000) mà PostgreSQL cấm, chuẩn hóa khoảng trắng thừa
            String sanitizedText = rawText.replace("\u0000", "")
                    .replaceAll("\r\n", "\n")
                    .replaceAll("[ \t]+", " ")
                    .replaceAll("\n{3,}", "\n\n")
                    .trim();

            log.info("Parse text thành công từ file: {}, độ dài: {} ký tự",
                    file.getOriginalFilename(), sanitizedText.length());

            return sanitizedText;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi khi bóc tách text bằng Apache Tika cho file: {}", file.getOriginalFilename(), e);
            throw new AppException(ErrorCode.FILE_PARSING_FAILED);
        }
    }
}
