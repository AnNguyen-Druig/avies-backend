package com.avies.backend.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GoF Singleton dùng riêng để đọc/ghi JSON snapshot.
 * Giữ nguyên cấu hình ObjectMapper mặc định đang sử dụng.
 */
public final class ExamSnapshotJsonCodec {

    // Một instance duy nhất trong phạm vi ClassLoader.
    private static final ExamSnapshotJsonCodec INSTANCE =
            new ExamSnapshotJsonCodec();

    private final ObjectMapper mapper;

    // Ngăn code bên ngoài gọi new.
    private ExamSnapshotJsonCodec() {
        this.mapper = new ObjectMapper();
    }

    // Điểm truy cập dùng chung.
    public static ExamSnapshotJsonCodec getInstance() {
        return INSTANCE;
    }

    public String writeValueAsString(Object value)
            throws JsonProcessingException {
        return mapper.writeValueAsString(value);
    }

    public <T> T readValue(String json, Class<T> type)
            throws JsonProcessingException {
        return mapper.readValue(json, type);
    }
}