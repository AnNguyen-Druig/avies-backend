package com.avies.backend.question;

import com.avies.backend.dto.ai.GeneratedQuestionDto;
import com.avies.backend.entity.enums.BloomLevel;
import com.avies.backend.service.impl.AiQuestionGenerationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Kiểm thử logic sinh câu hỏi và rubric của AiQuestionGenerationService")
class AiQuestionGenerationServiceTest {

    private final AiQuestionGenerationServiceImpl service = new AiQuestionGenerationServiceImpl();

    @Test
    @DisplayName("Ném lỗi AI_GENERATION_FAILED khi không có API key hợp lệ hoặc không thể gọi AI")
    void throwsExceptionWhenNoApiKeyConfigured() {
        String subjectName = "Cấu trúc dữ liệu và giải thuật";
        String keyword = "Cây nhị phân tìm kiếm";
        String context = "Cây nhị phân tìm kiếm (BST) là cây nhị phân...";

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.generateQuestions(
                        subjectName,
                        keyword,
                        context,
                        BloomLevel.APPLY,
                        2
                )
        ).isInstanceOf(com.avies.backend.exception.AppException.class)
         .hasFieldOrPropertyWithValue("errorCode", com.avies.backend.exception.ErrorCode.AI_GENERATION_FAILED);
    }
}
