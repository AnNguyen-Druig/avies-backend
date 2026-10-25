package com.avies.backend.service.impl;

import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.service.EmbeddingService;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingServiceImpl.class);

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${openai.embedding-model:text-embedding-3-small}")
    private String embeddingModel;

    private static final int VECTOR_DIMENSION = 1536;
    private static final int BATCH_SIZE = 50;

    private final RestClient restClient;

    public EmbeddingServiceImpl() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Tao vector embeddings cho danh sach cac text chunks thong qua OpenAI API.
     */
    @Override
    public List<float[]> generateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        if (apiKey == null || apiKey.trim().isEmpty()
                || "demo".equalsIgnoreCase(apiKey.trim())
                || "mock".equalsIgnoreCase(apiKey.trim())) {
            log.warn("OpenAI API Key chua duoc cau hinh. Sinh vector fallback cho {} chunks.", texts.size());
            return buildFallbackList(texts);
        }

        try {
            List<float[]> allEmbeddings = new ArrayList<>();
            for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
                List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
                allEmbeddings.addAll(callOpenAiEmbeddingsApi(batch));
            }
            return allEmbeddings;
        } catch (Exception e) {
            log.warn("OpenAI Embeddings API gap loi: {}. Kich hoat fallback cho {} chunks.",
                    e.getMessage(), texts.size());
            return buildFallbackList(texts);
        }
    }

    private List<float[]> buildFallbackList(List<String> texts) {
        List<float[]> list = new ArrayList<>();
        for (String text : texts) list.add(generateDeterministicFallbackVector(text));
        return list;
    }

    private List<float[]> callOpenAiEmbeddingsApi(List<String> batch) {
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "embeddings" : baseUrl + "/embeddings";
            Map<String, Object> requestBody = Map.of("model", embeddingModel, "input", batch);

            EmbeddingResponse response = restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(EmbeddingResponse.class);

            if (response == null || response.getData() == null || response.getData().isEmpty()) {
                log.error("OpenAI API tra ve du lieu rong cho batch {} items", batch.size());
                throw new AppException(ErrorCode.OPENAI_API_ERROR);
            }

            response.getData().sort(Comparator.comparingInt(EmbeddingData::getIndex));

            List<float[]> result = new ArrayList<>();
            for (EmbeddingData data : response.getData()) result.add(data.getEmbedding());
            return result;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Loi khi goi OpenAI Embeddings API (model={}): {}", embeddingModel, e.getMessage(), e);
            throw new AppException(ErrorCode.OPENAI_API_ERROR);
        }
    }

    /**
     * Sinh vector 1536 chieu chuan hoa (L2-norm=1.0) dua tren SHA-256 hash de fallback.
     */
    private float[] generateDeterministicFallbackVector(String text) {
        float[] vector = new float[VECTOR_DIMENSION];
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            Random rng = new Random(new java.math.BigInteger(1, hash).longValue());
            double normSq = 0.0;
            for (int i = 0; i < VECTOR_DIMENSION; i++) {
                vector[i] = (float) rng.nextGaussian();
                normSq += vector[i] * vector[i];
            }
            float norm = (float) Math.sqrt(normSq);
            if (norm > 0) for (int i = 0; i < VECTOR_DIMENSION; i++) vector[i] /= norm;
        } catch (Exception e) {
            Arrays.fill(vector, 0.001f);
        }
        return vector;
    }

    // DTO classes cho OpenAI Embeddings API response
    @Data
    public static class EmbeddingResponse {
        private String object;
        private List<EmbeddingData> data;
        private String model;
    }

    @Data
    public static class EmbeddingData {
        private int index;
        private float[] embedding;
        private String object;
    }
}