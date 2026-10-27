package com.avies.backend.exam;

import com.avies.backend.config.ExamLifecycleConfig;
import com.avies.backend.controller.ExamLifecycleController;
import com.avies.backend.repository.ExamTranscriptRepository;
import com.avies.backend.service.impl.ExamLifecycleServiceImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Context HTTP/JPA/JDBC thật, chỉ dùng PostgreSQL Docker từ nền móng selection hiện có. */
@Configuration
@Import({ExamQuestionSelectionPostgresConfig.class, ExamLifecycleConfig.class, ExamLifecycleController.class,
        ExamLifecycleServiceImpl.class, ExamTranscriptRepository.class})
public class ExamLifecyclePostgresConfig { }
