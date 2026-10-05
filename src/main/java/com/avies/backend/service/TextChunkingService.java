package com.avies.backend.service;

import java.util.List;

public interface TextChunkingService {

    /**
     * Băm văn bản dài thành danh sách các Chunks có độ dài phù hợp và overlap.
     */
    List<String> chunkText(String text);

    List<String> chunkText(String text, int chunkSize, int chunkOverlap);
}
