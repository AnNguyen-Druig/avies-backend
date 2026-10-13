package com.avies.backend.service;

import com.avies.backend.dto.ai.GeneratedQuestionDto;
import com.avies.backend.entity.enums.BloomLevel;

import java.util.List;

/**
 * Quy trình sinh câu hỏi AI (RAG Pipeline):
 * 1. Nhận từ khóa / chủ đề và mức độ Bloom từ request của giáo viên
 * 2. Đổi keyword thành vector embedding (text-embedding-3-small, 1536 dims)
 * 3. Tìm 5 đoạn tài liệu liên quan nhất qua cosine distance trên pgvector
 * 4. Ghép các đoạn thành contextText và truyền vào prompt
 * 5. Gọi GPT-4o (OpenAI) hoặc Gemini để sinh danh sách câu hỏi + rubric
 * 6. Parse JSON response thành {@code List<GeneratedQuestionDto>} có cấu trúc
 * 7. Trả kết quả về {@code QuestionService} để lưu DB và phản hồi client
 * Nếu không thể kết nối AI service hoặc thiếu API key, ném {@code AppException} (code 1025).
 */
public interface QuestionGenerationService {

    /**
     * Sinh danh sách câu hỏi và rubric tương ứng từ ngữ cảnh tài liệu và từ khóa.
     *
     * @param subjectName Tên môn học
     * @param keyword     Từ khóa / chủ đề bài học
     * @param contextText Ngữ cảnh từ 5 đoạn tài liệu liên quan nhất
     * @param bloomLevel  Mức độ Bloom mong muốn (nếu có)
     * @param count       Số lượng câu hỏi cần sinh
     * @return Danh sách câu hỏi kèm rubric có cấu trúc JSON
     */
    List<GeneratedQuestionDto> generateQuestions(
            String subjectName,
            String keyword,
            String contextText,
            BloomLevel bloomLevel,
            int count
    );
}
