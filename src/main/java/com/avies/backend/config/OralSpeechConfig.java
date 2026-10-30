package com.avies.backend.config;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Chuẩn bị audio hữu hạn, không chiếm worker STT/AI hoặc giữ thread HTTP. */
@Configuration
public class OralSpeechConfig {
    /** Khi executor đầy, yêu cầu nghe thông thường vẫn có thể tổng hợp theo nhu cầu. */
    @Bean public ThreadPoolTaskExecutor oralSpeechWarmupExecutor() {
        var executor=new ThreadPoolTaskExecutor(); executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("oral-speech-warmup-"); executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(40);
        return executor;
    }
}
