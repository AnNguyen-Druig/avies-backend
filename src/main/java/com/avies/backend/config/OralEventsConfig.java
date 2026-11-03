package com.avies.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Gửi SSE tách khỏi thread transaction/STT/AI; client chậm không chặn xử lý bài thi. */
@Configuration
@EnableScheduling
public class OralEventsConfig {
    @Bean public ThreadPoolTaskExecutor oralEventsExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("oral-events-"); executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
