package com.avies.backend.exam;

import com.avies.backend.controller.OralExamController;
import com.avies.backend.controller.OralExamRuntimeController;
import com.avies.backend.service.impl.OralExamRuntimeServiceImpl;
import com.avies.backend.repository.OralExamRepository;
import com.avies.backend.service.impl.OralExamServiceImpl;
import com.avies.backend.service.support.OralExamAccessPolicy;
import com.avies.backend.service.support.OralExamCoordinator;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Dùng lại hạ tầng PostgreSQL riêng của Feature 2; không nạp ứng dụng, .env hoặc provider thật. */
@Configuration
@Import({ExamLifecyclePostgresConfig.class, OralExamController.class, OralExamServiceImpl.class,
        OralExamRepository.class, OralExamAccessPolicy.class, OralExamCoordinator.class,
        OralExamRuntimeController.class, OralExamRuntimeServiceImpl.class,
        com.avies.backend.config.OralSpeechProperties.class,
        com.avies.backend.controller.OralSpeechController.class,
        com.avies.backend.service.impl.OralSpeechServiceImpl.class,
        com.avies.backend.service.impl.GeminiOralSpeechProvider.class,
        com.avies.backend.config.OralTranscriptionProperties.class,
        com.avies.backend.repository.OralAudioRepository.class,
        com.avies.backend.service.support.OralAudioStore.class,
        com.avies.backend.controller.OralTranscriptionController.class,
        com.avies.backend.service.impl.OralTranscriptionServiceImpl.class,
        com.avies.backend.service.impl.GroqOralTranscriptionProvider.class,
        com.avies.backend.config.OralDecisionProperties.class,
        com.avies.backend.repository.OralDecisionRepository.class})
public class OralExamPostgresConfig { }
