package com.avies.backend.service.impl;

import com.avies.backend.dto.ai.GeneratedQuestionDto;
import com.avies.backend.entity.enums.BloomLevel;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.service.QuestionGenerationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class QuestionGenerationServiceImpl implements QuestionGenerationService {

    @Value("${openai.api-key:}")
    String openAiApiKey;

    @Value("${openai.base-url:https://api.openai.com/v1}")
    String openAiBaseUrl;

    @Value("${openai.chat-model:gpt-4o}")
    String openAiChatModel;

    @Value("${gemini.api-key:}")
    String geminiApiKey;

    @Value("${gemini.model:gemini-3.5-flash-lite}")
    String geminiModel;

    final RestClient restClient;
    final ObjectMapper objectMapper;

    public QuestionGenerationServiceImpl() {
        this(new ObjectMapper());
    }

    public QuestionGenerationServiceImpl(ObjectMapper objectMapper) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(java.time.Duration.ofSeconds(60));

        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public List<GeneratedQuestionDto> generateQuestions(
            String subjectName,
            String keyword,
            String contextText,
            BloomLevel bloomLevel,
            int count
    ) {
        int targetCount = Math.max(1, Math.min(count, 10));

        // 1. Nếu có OpenAI API key hợp lệ -> gọi OpenAI GPT-4o
        if (isValidApiKey(openAiApiKey)) {
            try {
                log.info("Gọi OpenAI Chat Completion (model={}) để sinh {} câu hỏi cho keyword '{}'",
                        openAiChatModel, targetCount, keyword);
                return callOpenAiChatCompletion(subjectName, keyword, contextText, bloomLevel, targetCount);
            } catch (Exception e) {
                log.error("Lỗi khi gọi OpenAI API: {}. Đang kiểm tra Gemini hoặc fallback.", e.getMessage(), e);
            }
        }

        // 2. Nếu có Gemini API key -> gọi Gemini API
        if (isValidApiKey(geminiApiKey)) {
            try {
                log.info("Gọi Google Gemini API (model={}) để sinh {} câu hỏi cho keyword '{}'",
                        geminiModel, targetCount, keyword);
                return callGeminiApi(subjectName, keyword, contextText, bloomLevel, targetCount);
            } catch (Exception e) {
                log.error("Lỗi khi gọi Gemini API: {}. Chuyển sang fallback.", e.getMessage(), e);
            }
        }

        // 3. Nếu không có API key hợp lệ hoặc gọi dịch vụ AI thất bại -> Báo lỗi yêu cầu thực hiện lại
        log.error("Không có API key OpenAI/Gemini hợp lệ hoặc các dịch vụ AI đều gọi thất bại.");
        throw new AppException(ErrorCode.AI_GENERATION_FAILED,
                "Không thể kết nối hoặc gọi dịch vụ AI để sinh câu hỏi lúc này (vui lòng kiểm tra cấu hình API key hoặc thực hiện lại quá trình tạo câu hỏi).");
    }

    private boolean isValidApiKey(String key) {
        return key != null && !key.trim().isEmpty()
                && !"demo".equalsIgnoreCase(key.trim())
                && !"mock".equalsIgnoreCase(key.trim());
    }

    /**
     * Gọi OpenAI Chat Completions API với response_format = json_object
     */
    private List<GeneratedQuestionDto> callOpenAiChatCompletion(
            String subjectName,
            String keyword,
            String contextText,
            BloomLevel bloomLevel,
            int count
    ) {
        String url = openAiBaseUrl.endsWith("/") ? openAiBaseUrl + "chat/completions" : openAiBaseUrl + "/chat/completions";

        String prompt = buildPrompt(subjectName, keyword, contextText, bloomLevel, count);

        Map<String, Object> requestBody = Map.of(
                "model", openAiChatModel,
                "messages", List.of(
                        Map.of("role", "system", "content", "Bạn là chuyên gia giảng viên biên soạn đề thi vấn đáp đại học. Hãy luôn trả kết quả đúng định dạng JSON được yêu cầu."),
                        Map.of("role", "user", "content", prompt)
                ),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.7
        );

        String rawResponse = restClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey.trim())
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return parseOpenAiResponse(rawResponse, bloomLevel, count);
    }

    /**
     * Gọi Google Gemini generateContent API
     */
    private List<GeneratedQuestionDto> callGeminiApi(
            String subjectName,
            String keyword,
            String contextText,
            BloomLevel bloomLevel,
            int count
    ) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel
                + ":generateContent?key=" + geminiApiKey.trim();

        String prompt = buildPrompt(subjectName, keyword, contextText, bloomLevel, count);

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                ),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json"
                )
        );

        String rawResponse = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return parseGeminiResponse(rawResponse, bloomLevel, count);
    }

    private String buildPrompt(String subjectName, String keyword, String contextText, BloomLevel bloomLevel, int count) {
        String bloomConstraint = (bloomLevel != null)
                ? "Tất cả các câu hỏi bắt buộc phải thuộc mức nhận thức Bloom: " + bloomLevel.name() + "."
                : "Hãy phân bổ mức nhận thức Bloom phù hợp (REMEMBER, UNDERSTAND, APPLY, ANALYZE, EVALUATE hoặc CREATE) cho từng câu hỏi dựa trên độ phức tạp.";

        return """
                Bạn là giảng viên đại học phụ trách môn '%s'.
                Hãy tạo đúng %d câu hỏi vấn đáp (oral exam) kèm đáp án tham khảo dựa trên tài liệu học tập được cung cấp dưới đây.
                
                Từ khóa / Chủ đề yêu cầu: '%s'
                Yêu cầu mức độ Bloom: %s
                
                --- BẮT ĐẦU NGỮ CẢNH TÀI LIỆU (CONTEXT) ---
                %s
                --- KẾT THÚC NGỮ CẢNH TÀI LIỆU ---
                
                QUY TẮC BẮT BUỘC:
                1. Nội dung câu hỏi (content): Bám sát và phản ánh chính xác kiến thức trong ngữ cảnh tài liệu ở trên.
                2. Đáp án tham khảo (referenceAnswer): Đầy đủ các ý chính để giảng viên dùng làm căn cứ hỏi thi và đối chiếu câu trả lời.
                3. Mức độ Bloom (bloomLevel): Chỉ chấp nhận một trong 6 giá trị: 'REMEMBER', 'UNDERSTAND', 'APPLY', 'ANALYZE', 'EVALUATE', 'CREATE'.
                4. Danh sách chủ đề (topics): Gồm 1-3 từ khóa/nhãn liên quan.
                5. Thời gian trả lời (answerTimeLimitSeconds): Từ 60 đến 300 giây (ví dụ 120, 180).
                
                Trả về DUY NHẤT một đối tượng JSON có cấu trúc chính xác như sau:
                {
                  "questions": [
                    {
                      "content": "Nội dung câu hỏi vấn đáp...",
                      "referenceAnswer": "Dàn ý câu trả lời chuẩn...",
                      "bloomLevel": "UNDERSTAND",
                      "topics": ["Chủ đề A", "Chủ đề B"],
                      "answerTimeLimitSeconds": 120
                    }
                  ]
                }
                """.formatted(
                subjectName != null ? subjectName : "Chuyên ngành",
                count,
                keyword != null ? keyword : "Kiến thức chung",
                bloomConstraint,
                (contextText != null && !contextText.isBlank()) ? contextText : "Không có thêm nội dung."
        );
    }

    private List<GeneratedQuestionDto> parseOpenAiResponse(String rawResponse, BloomLevel requestedBloom, int targetCount) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                String contentJson = choices.get(0).path("message").path("content").asText();
                return parseQuestionsJson(contentJson, requestedBloom, targetCount);
            }
            throw new AppException(ErrorCode.AI_GENERATION_FAILED);
        } catch (AppException ae) {
            throw ae;
        } catch (Exception e) {
            log.error("Lỗi khi parse phản hồi từ OpenAI: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.AI_GENERATION_FAILED);
        }
    }

    private List<GeneratedQuestionDto> parseGeminiResponse(String rawResponse, BloomLevel requestedBloom, int targetCount) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        String text = part.path("text").asText("");
                        if (!text.isBlank()) {
                            return parseQuestionsJson(text, requestedBloom, targetCount);
                        }
                    }
                }
            }
            throw new AppException(ErrorCode.AI_GENERATION_FAILED);
        } catch (AppException ae) {
            throw ae;
        } catch (Exception e) {
            log.error("Lỗi khi parse phản hồi từ Gemini: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.AI_GENERATION_FAILED);
        }
    }

    private List<GeneratedQuestionDto> parseQuestionsJson(String contentJson, BloomLevel requestedBloom, int targetCount) {
        try {
            String sanitizedJson = contentJson.trim();
            if (sanitizedJson.startsWith("```json")) {
                sanitizedJson = sanitizedJson.substring(7);
            } else if (sanitizedJson.startsWith("```")) {
                sanitizedJson = sanitizedJson.substring(3);
            }
            if (sanitizedJson.endsWith("```")) {
                sanitizedJson = sanitizedJson.substring(0, sanitizedJson.length() - 3);
            }
            sanitizedJson = sanitizedJson.trim();

            JsonNode root = objectMapper.readTree(sanitizedJson);
            JsonNode questionsNode = root.has("questions") ? root.get("questions") : root;

            List<GeneratedQuestionDto> questions = new ArrayList<>();
            if (questionsNode.isArray()) {
                for (JsonNode qNode : questionsNode) {
                    GeneratedQuestionDto q = parseSingleQuestion(qNode, requestedBloom);
                    if (q != null) {
                        questions.add(q);
                    }
                }
            } else if (questionsNode.isObject()) {
                GeneratedQuestionDto q = parseSingleQuestion(questionsNode, requestedBloom);
                if (q != null) {
                    questions.add(q);
                }
            }

            if (questions.isEmpty()) {
                throw new AppException(ErrorCode.AI_GENERATION_FAILED);
            }

            return questions;
        } catch (AppException ae) {
            throw ae;
        } catch (Exception e) {
            log.error("Lỗi khi parse JSON câu hỏi: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.AI_GENERATION_FAILED);
        }
    }

    private GeneratedQuestionDto parseSingleQuestion(JsonNode qNode, BloomLevel requestedBloom) {
        String content = qNode.path("content").asText(null);
        if (content == null || content.isBlank()) {
            return null;
        }

        String referenceAnswer = qNode.path("referenceAnswer").asText(null);

        // Bloom Level
        BloomLevel bloom = requestedBloom;
        if (bloom == null) {
            String bloomStr = qNode.path("bloomLevel").asText("");
            bloom = parseBloomLevel(bloomStr);
        }

        // Topics
        Set<String> topics = new LinkedHashSet<>();
        JsonNode topicsNode = qNode.path("topics");
        if (topicsNode.isArray()) {
            for (JsonNode t : topicsNode) {
                String val = t.asText("").trim();
                if (!val.isBlank()) {
                    topics.add(val);
                }
            }
        }

        int answerTimeLimit = qNode.path("answerTimeLimitSeconds").asInt(120);
        if (answerTimeLimit <= 0) {
            answerTimeLimit = 120;
        }

        return GeneratedQuestionDto.builder()
                .content(content)
                .referenceAnswer(referenceAnswer)
                .bloomLevel(bloom)
                .topics(topics)
                .answerTimeLimitSeconds(answerTimeLimit)
                .build();
    }


    private BloomLevel parseBloomLevel(String text) {
        if (text == null || text.isBlank()) return BloomLevel.UNDERSTAND;
        try {
            return BloomLevel.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return BloomLevel.UNDERSTAND;
        }
    }
}
