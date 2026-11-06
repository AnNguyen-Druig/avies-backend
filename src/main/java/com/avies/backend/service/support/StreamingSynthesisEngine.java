package com.avies.backend.service.support;

import java.util.function.Consumer;

/** Session TTS chỉ được một borrower dùng; adapter phải gỡ callback sau mỗi request. */
public interface StreamingSynthesisEngine {
    Session open(String apiKey, String region, String voice);
    interface Session extends AutoCloseable {
        void preconnect();
        void stream(String ssml, Consumer<byte[]> sink);
        @Override void close();
    }
}
