package com.avies.backend.config;

import com.avies.backend.controller.OralAudioWebSocket;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration @EnableWebSocket @RequiredArgsConstructor
public class OralStreamingConfig implements WebSocketConfigurer, org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
    private final OralAudioWebSocket handler;
    private final CorsProperties cors;
    @org.springframework.context.annotation.Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor oralStreamingHttpExecutor() {
        var executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2); executor.setMaxPoolSize(4); executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("oral-tts-http-"); executor.setWaitForTasksToCompleteOnShutdown(false); return executor;
    }
    @Override public void configureAsyncSupport(org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(oralStreamingHttpExecutor()).setDefaultTimeout(45000);
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var origins = cors.getAllowedOriginPatterns().stream().filter(s -> !s.equals("*")).toList();
        registry.addHandler(handler, "/student-exams/{id}/oral-examination/stream")
                .setAllowedOriginPatterns((origins.isEmpty() ? java.util.List.of("http://localhost:5173", "http://127.0.0.1:5173") : origins).toArray(String[]::new));
    }
}
