package com.avies.backend.exam;

import com.avies.backend.config.CorsProperties;
import com.avies.backend.config.SecurityConfig;
import com.avies.backend.controller.ExamSessionController;
import com.avies.backend.controller.LecturerSubjectAssignmentController;
import com.avies.backend.entity.*;
import com.avies.backend.entity.Role;
import com.avies.backend.exception.exceptions.ExamSessionExceptionHandler;
import com.avies.backend.exception.exceptions.AssignmentExceptionHandler;
import com.avies.backend.repository.ExamSessionRepository;
import com.avies.backend.service.impl.ExamSessionServiceImpl;
import com.avies.backend.service.support.ExamAccessPolicy;
import com.avies.backend.service.support.ExamSnapshotSupport;
import com.avies.backend.service.support.ExamQuestionEligibility;
import com.avies.backend.service.impl.LecturerSubjectAssignmentServiceImpl;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

/** Web/service/repository thật trên Docker; không nạp cấu hình ứng dụng, .env hoặc gọi dịch vụ AI. */
@Configuration
@EnableWebMvc
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = ExamSessionRepository.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = ".*(LearningMaterial|InvalidatedToken|RefreshToken).*"))
@Import({ExamSessionController.class, ExamSessionServiceImpl.class, ExamSessionExceptionHandler.class, ExamAccessPolicy.class, ExamSnapshotSupport.class, ExamQuestionEligibility.class,
        LecturerSubjectAssignmentController.class, LecturerSubjectAssignmentServiceImpl.class,
        AssignmentExceptionHandler.class, SecurityConfig.class, CorsProperties.class})
public class ExamSessionCrudPostgresConfig {
    /** Container riêng theo image đã khóa digest ở nền móng P0.3. */
    @Bean(initMethod = "start", destroyMethod = "stop")
    PostgreSQLContainer postgres() {
        return ExamFoundationPostgresConfig.newPostgres();
    }

    /** Chuẩn bị baseline cũ và chạy các file migration thật trong container trước test. */
    @Bean
    DataSource dataSource(PostgreSQLContainer postgres) throws Exception {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Connection connection = source.getConnection()) {
            ExamFoundationPostgresConfig.legacySchema(connection);
            ExamFoundationPostgresConfig.execute(connection, ExamFoundationPostgresConfig.projectSql(
                    "docs/migrations/20261016_feature2_exam_foundation.sql"));
            ExamFoundationPostgresConfig.currentExamSchema(connection);
        }
        return source;
    }

    /** Chỉ validate schema đã migration; không dùng Hibernate để thay đổi database. */
    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(User.class.getName(), Role.class.getName(), Subject.class.getName(),
                Question.class.getName(), Rubric.class.getName(), RubricCriterion.class.getName(), QuestionAuditLog.class.getName(),
                LecturerSubjectAssignment.class.getName(), ExamSession.class.getName(), StudentExam.class.getName(), StudentExamQuestion.class.getName()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", "aives"));
        return factory;
    }

    /** Mỗi HTTP request có transaction riêng, cho phép kiểm tra commit/rollback và khóa thật. */
    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }

    /** Bật validation cho cả lời gọi service trực tiếp. */
    @Bean
    static MethodValidationPostProcessor methodValidationPostProcessor() {
        return new MethodValidationPostProcessor();
    }

}
