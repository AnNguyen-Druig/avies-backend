package com.avies.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class TextChunkingService {

    // Kích thước chunk mục tiêu (~800 - 1000 ký tự phù hợp cho RAG & text-embedding-3-small)
    private static final int DEFAULT_CHUNK_SIZE = 900;
    // Độ dài overlap giữa các chunk kế tiếp để tránh mất ngữ cảnh ở ranh giới
    private static final int DEFAULT_CHUNK_OVERLAP = 150;
    // Độ dài tối thiểu của một chunk có ý nghĩa
    private static final int MIN_CHUNK_LENGTH = 30;

    /**
     * Băm văn bản dài thành danh sách các Chunks có độ dài phù hợp và overlap.
     */
    public List<String> chunkText(String text) {
        return chunkText(text, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    public List<String> chunkText(String text, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        String cleanedText = text.trim();
        int textLength = cleanedText.length();

        if (textLength <= chunkSize) {
            chunks.add(cleanedText);
            return chunks;
        }

        int start = 0;
        while (start < textLength) {
            int end = Math.min(start + chunkSize, textLength);

            // Nếu chưa tới cuối văn bản, cố gắng tìm điểm ngắt tự nhiên (hết đoạn, hết câu, hoặc hết từ)
            if (end < textLength) {
                int boundary = findNaturalBreakPoint(cleanedText, start, end);
                if (boundary > start + MIN_CHUNK_LENGTH) {
                    end = boundary;
                }
            }

            String chunk = cleanedText.substring(start, end).trim();
            if (chunk.length() >= MIN_CHUNK_LENGTH) {
                chunks.add(chunk);
            }

            if (end >= textLength) {
                break;
            }

            // Tính điểm bắt đầu tiếp theo với overlap
            start = Math.max(start + 1, end - chunkOverlap);
        }

        log.info("Văn bản độ dài {} ký tự được băm thành {} chunks (chunkSize={}, overlap={})",
                textLength, chunks.size(), chunkSize, chunkOverlap);
        return chunks;
    }

    private int findNaturalBreakPoint(String text, int start, int targetEnd) {
        // Ưu tiên 1: Hết đoạn (\n\n) trong phạm vi 150 ký tự trước targetEnd
        int paragraphBreak = text.lastIndexOf("\n\n", targetEnd);
        if (paragraphBreak > start + (targetEnd - start) / 2) {
            return paragraphBreak + 2;
        }

        // Ưu tiên 2: Xuống dòng (\n)
        int lineBreak = text.lastIndexOf('\n', targetEnd);
        if (lineBreak > start + (targetEnd - start) / 2) {
            return lineBreak + 1;
        }

        // Ưu tiên 3: Dấu chấm câu (. ! ?) kèm khoảng trắng
        for (int i = targetEnd; i >= start + (targetEnd - start) / 2; i--) {
            char c = text.charAt(i - 1);
            if ((c == '.' || c == '?' || c == '!') && (i == text.length() || Character.isWhitespace(text.charAt(i)))) {
                return i;
            }
        }

        // Ưu tiên 4: Dấu khoảng trắng giữa các từ
        int spaceBreak = text.lastIndexOf(' ', targetEnd);
        if (spaceBreak > start + (targetEnd - start) / 2) {
            return spaceBreak + 1;
        }

        return targetEnd;
    }
}
