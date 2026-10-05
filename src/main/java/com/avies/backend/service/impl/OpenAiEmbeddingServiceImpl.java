package com.avies.backend.service.impl;

import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.service.OpenAiEmbeddingService;
import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class OpenAiEmbeddingServiceImpl implements  OpenAiEmbeddingService {

    @Value("${openai.api-key:}")
    String apiKey;

    @Value("${openai.base-url:https://api.openai.com/v1}")
    String baseUrl;

    @Value("${openai.embedding-model:text-embedding-3-small}")
    String embeddingModel;

    static final int VECTOR_DIMENSION = 1536;
    static final int BATCH_SIZE = 50;

    final RestClient restClient;

    public OpenAiEmbeddingServiceImpl() {
        this.restClient = RestClient.builder().build();
    }

    /**
     * Tạo vector embeddings cho danh sách các text chunks thông qua OpenAI API (model: text-embedding-3-small).
     */
    public List<float[]> generateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        // Nếu API key chưa được cấu hình hoặc là demo/mock, tạo vector giả lập chuẩn hóa 1536 dims để hệ thống không crash
        if (apiKey == null || apiKey.trim().isEmpty() || "demo".equalsIgnoreCase(apiKey.trim()) || "mock".equalsIgnoreCase(apiKey.trim())) {
            log.warn("OpenAI API Key chưa được cấu hình trong application.yaml (hoặc biến OPENAI_API_KEY). Sinh vector embedding dự phòng cho {} chunks.", texts.size());
            List<float[]> fallbackList = new ArrayList<>();
            for (String text : texts) {
                fallbackList.add(generateDeterministicFallbackVector(text));
            }
            return fallbackList;
        }

        List<float[]> allEmbeddings = new ArrayList<>();

        // Xử lý theo từng batch để tối ưu hóa mạng và tuân thủ giới hạn payload của OpenAI
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            int toIndex = Math.min(i + BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, toIndex);

            List<float[]> batchEmbeddings = callOpenAiEmbeddingsApi(batch);
            allEmbeddings.addAll(batchEmbeddings);
        }

        return allEmbeddings;
    }

    private List<float[]> callOpenAiEmbeddingsApi(List<String> batch) {
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "embeddings" : baseUrl + "/embeddings";

            Map<String, Object> requestBody = Map.of(
                    "model", embeddingModel,
                    "input", batch
            );

            OpenAiEmbeddingService.OpenAiEmbeddingResponse response = restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(OpenAiEmbeddingService.OpenAiEmbeddingResponse.class);

            if (response == null || response.getData() == null || response.getData().isEmpty()) {
                log.error("OpenAI API trả về dữ liệu rỗng cho batch {} items", batch.size());
                throw new AppException(ErrorCode.OPENAI_API_ERROR);
            }

            // Đảm bảo thứ tự vector khớp với index
            response.getData().sort(Comparator.comparingInt(OpenAiEmbeddingService.OpenAiEmbeddingData::getIndex));

            List<float[]> result = new ArrayList<>();
            for (OpenAiEmbeddingService.OpenAiEmbeddingData data : response.getData()) {
                result.add(data.getEmbedding());
            }

            return result;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi khi gọi OpenAI Embeddings API (model={}): {}", embeddingModel, e.getMessage(), e);
            throw new AppException(ErrorCode.OPENAI_API_ERROR);
        }
    }

    /**
     * Sinh vector 1536 chiều được chuẩn hóa (L2-norm = 1.0) dựa trên SHA-256 hash của text
     * để phục vụ testing và development khi chưa có OpenAI API key thật.
     */
    private float[] generateDeterministicFallbackVector(String text) {
        float[] vector = new float[VECTOR_DIMENSION];
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            Random rng = new Random(new java.math.BigInteger(1, hash).longValue());

            double normSq = 0.0;
            for (int i = 0; i < VECTOR_DIMENSION; i++) {
                vector[i] = (float) (rng.nextGaussian());
                normSq += vector[i] * vector[i];
            }

            // Chuẩn hóa L2 norm = 1.0
            float norm = (float) Math.sqrt(normSq);
            if (norm > 0) {
                for (int i = 0; i < VECTOR_DIMENSION; i++) {
                    vector[i] /= norm;
                }
            }
        } catch (Exception e) {
            Arrays.fill(vector, 0.001f);
        }
        return vector;
    }
}
