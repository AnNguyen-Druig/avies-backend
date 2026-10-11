package com.avies.backend.service;

import com.avies.backend.dto.ai.GeneratedQuestionDto;
import com.avies.backend.entity.enums.BloomLevel;

import java.util.List;

/**
 * Hợp đồng dịch vụ AI sinh câu hỏi vấn đáp và rubric đánh giá từ tài liệu học tập qua RAG.
 */
public interface AiQuestionGenerationService {

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
