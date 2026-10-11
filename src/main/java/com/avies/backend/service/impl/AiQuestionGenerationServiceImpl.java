package com.avies.backend.service.impl;

import com.avies.backend.dto.ai.GeneratedQuestionDto;
import com.avies.backend.entity.enums.BloomLevel;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.service.AiQuestionGenerationService;
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
public class AiQuestionGenerationServiceImpl implements AiQuestionGenerationService {

    @Value("${openai.api-key:}")
    String openAiApiKey;

    @Value("${openai.base-url:https://api.openai.com/v1}")
    String openAiBaseUrl;

    @Value("${openai.chat-model:gpt-4o}")
    String openAiChatModel;

    @Value("${gemini.api-key:}")
    String geminiApiKey;

    @Value("${gemini.model:gemini-3.8-flash}")
    String geminiModel;

    final RestClient restClient;
    final ObjectMapper objectMapper;

    public AiQuestionGenerationServiceImpl() {
        this.restClient = RestClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }

    public AiQuestionGenerationServiceImpl(ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().build();
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
                Hãy tạo đúng %d câu hỏi vấn đáp (oral exam) kèm đáp án tham khảo và rubric chấm điểm chi tiết dựa trên tài liệu học tập được cung cấp dưới đây.
                
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
                6. Rubric chấm điểm:
                   - title: Tiêu đề ngắn gọn cho rubric.
                   - description: Hướng dẫn ngắn cho giảng viên.
                   - criteria: Danh sách 3-4 tiêu chí cụ thể.
                     * criterionText: Nội dung chi tiết tiêu chí.
                     * pointWeight: Điểm trọng số (số thập phân, ví dụ 3.0, 4.0).
                       CỰC KỲ QUAN TRỌNG: Tổng điểm pointWeight của toàn bộ tiêu chí trong 1 rubric BẮT BUỘC PHẢI BẰNG CHÍNH XÁC 10.0 (thang điểm 10).
                     * isMandatory: false
                     * displayOrder: 1, 2, 3...
                
                Trả về DUY NHẤT một đối tượng JSON có cấu trúc chính xác như sau:
                {
                  "questions": [
                    {
                      "content": "Nội dung câu hỏi vấn đáp...",
                      "referenceAnswer": "Dàn ý câu trả lời chuẩn...",
                      "bloomLevel": "UNDERSTAND",
                      "topics": ["Chủ đề A", "Chủ đề B"],
                      "answerTimeLimitSeconds": 120,
                      "rubric": {
                        "title": "Rubric đánh giá câu hỏi...",
                        "description": "Hướng dẫn chấm thi...",
                        "criteria": [
                          { "criterionText": "Tiêu chí 1", "pointWeight": 4.0, "isMandatory": false, "displayOrder": 1 },
                          { "criterionText": "Tiêu chí 2", "pointWeight": 4.0, "isMandatory": false, "displayOrder": 2 },
                          { "criterionText": "Tiêu chí 3", "pointWeight": 2.0, "isMandatory": false, "displayOrder": 3 }
                        ]
                      }
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
                if (parts.isArray() && !parts.isEmpty()) {
                    String contentJson = parts.get(0).path("text").asText();
                    return parseQuestionsJson(contentJson, requestedBloom, targetCount);
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
            JsonNode root = objectMapper.readTree(contentJson);
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

        // Rubric
        GeneratedQuestionDto.GeneratedRubricDto rubricDto = parseRubric(qNode.path("rubric"), content);

        return GeneratedQuestionDto.builder()
                .content(content)
                .referenceAnswer(referenceAnswer)
                .bloomLevel(bloom)
                .topics(topics)
                .answerTimeLimitSeconds(answerTimeLimit)
                .rubric(rubricDto)
                .build();
    }

    private GeneratedQuestionDto.GeneratedRubricDto parseRubric(JsonNode rubricNode, String questionContent) {
        String title = rubricNode.path("title").asText("Rubric chấm điểm");
        String description = rubricNode.path("description").asText("Đánh giá phần trả lời vấn đáp của sinh viên");

        List<GeneratedQuestionDto.GeneratedCriterionDto> criteria = new ArrayList<>();
        JsonNode criteriaNode = rubricNode.path("criteria");

        if (criteriaNode.isArray() && !criteriaNode.isEmpty()) {
            int order = 1;
            for (JsonNode cNode : criteriaNode) {
                String text = cNode.path("criterionText").asText("").trim();
                if (text.isBlank()) continue;

                double weightVal = cNode.path("pointWeight").asDouble(0.0);
                BigDecimal weight = BigDecimal.valueOf(weightVal).setScale(2, RoundingMode.HALF_UP);
                boolean mandatory = cNode.path("isMandatory").asBoolean(false);

                criteria.add(GeneratedQuestionDto.GeneratedCriterionDto.builder()
                        .criterionText(text)
                        .pointWeight(weight)
                        .isMandatory(mandatory)
                        .displayOrder(order++)
                        .build());
            }
        }

        // Nếu criteria trống hoặc không hợp lệ -> sinh mặc định 3 tiêu chí
        if (criteria.isEmpty()) {
            criteria.add(GeneratedQuestionDto.GeneratedCriterionDto.builder()
                    .criterionText("Nắm vững kiến thức và định nghĩa cốt lõi")
                    .pointWeight(new BigDecimal("4.00"))
                    .isMandatory(false)
                    .displayOrder(1)
                    .build());
            criteria.add(GeneratedQuestionDto.GeneratedCriterionDto.builder()
                    .criterionText("Giải thích, phân tích và liên hệ thực tế chính xác")
                    .pointWeight(new BigDecimal("4.00"))
                    .isMandatory(false)
                    .displayOrder(2)
                    .build());
            criteria.add(GeneratedQuestionDto.GeneratedCriterionDto.builder()
                    .criterionText("Kỹ năng diễn đạt, trả lời rõ ràng và tự tin")
                    .pointWeight(new BigDecimal("2.00"))
                    .isMandatory(false)
                    .displayOrder(3)
                    .build());
        } else {
            // Chuẩn hóa để tổng điểm các tiêu chí PHẢI CHÍNH XÁC = 10.00
            normalizeCriteriaPointsToTen(criteria);
        }

        return GeneratedQuestionDto.GeneratedRubricDto.builder()
                .title(title)
                .description(description)
                .criteria(criteria)
                .build();
    }

    /**
     * Chuẩn hóa tổng điểm của danh sách tiêu chí trong Rubric về chính xác 10.00
     */
    private void normalizeCriteriaPointsToTen(List<GeneratedQuestionDto.GeneratedCriterionDto> criteria) {
        BigDecimal sum = BigDecimal.ZERO;
        for (GeneratedQuestionDto.GeneratedCriterionDto c : criteria) {
            if (c.getPointWeight() == null || c.getPointWeight().compareTo(BigDecimal.ZERO) <= 0) {
                c.setPointWeight(new BigDecimal("1.00"));
            }
            sum = sum.add(c.getPointWeight());
        }

        BigDecimal targetTotal = new BigDecimal("10.00");
        if (sum.compareTo(targetTotal) != 0) {
            BigDecimal runningSum = BigDecimal.ZERO;
            for (int i = 0; i < criteria.size(); i++) {
                GeneratedQuestionDto.GeneratedCriterionDto c = criteria.get(i);
                if (i == criteria.size() - 1) {
                    // Tiêu chí cuối cùng gán bằng số dư còn lại để tổng đúng 10.00
                    BigDecimal lastWeight = targetTotal.subtract(runningSum).setScale(2, RoundingMode.HALF_UP);
                    if (lastWeight.compareTo(BigDecimal.ZERO) <= 0) {
                        lastWeight = new BigDecimal("0.50");
                    }
                    c.setPointWeight(lastWeight);
                } else {
                    BigDecimal normalized = c.getPointWeight()
                            .multiply(targetTotal)
                            .divide(sum, 2, RoundingMode.HALF_UP);
                    c.setPointWeight(normalized);
                    runningSum = runningSum.add(normalized);
                }
            }
        }
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
