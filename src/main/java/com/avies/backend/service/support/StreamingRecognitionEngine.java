package com.avies.backend.service.support;

/** Adapter SDK có thể thay bằng engine điều khiển được trong test; không giả callback qua HTTP. */
public interface StreamingRecognitionEngine {
    Channel open(String language, Listener listener);
    interface Listener {
        void partial(String text);
        void recognized(String resultId, String text);
        void stopped();
        void failed(int errorCode);
    }
    interface Channel extends AutoCloseable {
        void write(byte[] pcm);
        void endInput();
        @Override void close();
    }
}
