package com.avies.backend.config;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Worker chỉ bật gọi STT khi có flag/key; executor hữu hạn không tạo hàng đợi audio vô hạn. */
@Configuration @EnableScheduling
public class OralTranscriptionConfig {
    /** Hai worker, không giữ row lock hoặc transaction trong lúc chờ mạng. */
    @Bean public ThreadPoolTaskExecutor oralSttExecutor() {
        var executor = new ThreadPoolTaskExecutor(); executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("oral-stt-"); executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(40);
        return executor;
    }
}
