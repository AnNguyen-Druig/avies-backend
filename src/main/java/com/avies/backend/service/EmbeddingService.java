package com.avies.backend.service;

import java.util.List;

/**
 * Service tao vector embeddings cho text thong qua OpenAI API.
 */
public interface EmbeddingService {

    /**
     * Tao vector embeddings cho danh sach cac text chunks.
     */
    List<float[]> generateEmbeddings(List<String> texts);
}