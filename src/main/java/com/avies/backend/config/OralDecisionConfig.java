package com.avies.backend.config;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Giới hạn số cuộc gọi AI đang chờ, không mở hàng đợi không giới hạn. */
@Configuration @EnableScheduling
public class OralDecisionConfig {
    /** Hai worker riêng, không giữ transaction khi gọi Google. */
    @Bean public ThreadPoolTaskExecutor oralDecisionExecutor() {
        var executor=new ThreadPoolTaskExecutor(); executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("oral-decision-"); executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(40);
        return executor;
    }
}
