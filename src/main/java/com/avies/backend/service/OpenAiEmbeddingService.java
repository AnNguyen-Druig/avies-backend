package com.avies.backend.service;

import lombok.Data;
import java.util.*;

public interface OpenAiEmbeddingService {

    /**
     * Tạo vector embeddings cho danh sách các text chunks thông qua OpenAI API (model: text-embedding-3-small).
     */
    List<float[]> generateEmbeddings(List<String> texts);

    @Data
    public static class OpenAiEmbeddingResponse {
        String object;
        List<OpenAiEmbeddingData> data;
        String model;
    }

    @Data
    public static class OpenAiEmbeddingData {
        int index;
        float[] embedding;
        String object;
    }
}
