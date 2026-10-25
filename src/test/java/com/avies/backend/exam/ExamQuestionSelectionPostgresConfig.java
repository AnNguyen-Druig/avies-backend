package com.avies.backend.exam;

import com.avies.backend.service.impl.ExamQuestionSelectionServiceImpl;
import com.avies.backend.service.support.*;
import org.springframework.context.annotation.*;
import java.util.Random;

/** Context dùng schema/migration thật trong PostgreSQL tạm; không nạp application.yaml hay Supabase. */
@Configuration
@Import({StudentAssignmentPostgresConfig.class, ExamQuestionSelectionServiceImpl.class, ExamQuestionEligibility.class,
        ExamSnapshotSupport.class, com.avies.backend.service.impl.ExamSnapshotServiceImpl.class})
public class ExamQuestionSelectionPostgresConfig {
    /** Nguồn random cố định chỉ trong test, production vẫn dùng SecureRandom. */
    @Bean
    ExamQuestionSelector examQuestionSelector() {
        return new ExamQuestionSelector(new Random(29));
    }
}
