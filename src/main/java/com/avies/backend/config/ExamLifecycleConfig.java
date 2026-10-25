package com.avies.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

/** Đồng hồ máy chủ dùng chung cho các quyết định thời gian của vòng đời thi. */
@Configuration
public class ExamLifecycleConfig {
    /** Có thể thay đồng hồ trong test mà không sửa lịch hoặc đồng hồ hệ điều hành. */
    @Bean
    public Clock examLifecycleClock() {
        return Clock.systemUTC();
    }
}
