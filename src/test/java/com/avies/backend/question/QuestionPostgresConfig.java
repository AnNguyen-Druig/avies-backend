package com.avies.backend.question;

import com.avies.backend.config.*;
import com.avies.backend.controller.AuthenticationController;
import com.avies.backend.controller.QuestionController;
import com.avies.backend.entity.*;
import com.avies.backend.entity.Role;
import com.avies.backend.exception.GlobalExceptionHandler;
import com.avies.backend.exception.QuestionExceptionHandler;
import com.avies.backend.repository.QuestionRepository;
import com.avies.backend.service.impl.AuthenticationServiceImpl;
import com.avies.backend.service.impl.QuestionServiceImpl;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
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
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** Ngữ cảnh web thật trên PostgreSQL tạm; không đọc application.yaml và không khởi tạo dịch vụ AI. */
@Configuration
@EnableWebMvc
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = QuestionRepository.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = ".*(LearningMaterial|RefreshToken).*"))
@Import({QuestionController.class, AuthenticationController.class, QuestionServiceImpl.class,
        AuthenticationServiceImpl.class, SecurityConfig.class, CorsProperties.class,
        CustomJwtDecoder.class, QuestionExceptionHandler.class, GlobalExceptionHandler.class})
public class QuestionPostgresConfig {
    /** Digest cố định để các thành viên chạy cùng PostgreSQL 17/pgvector, không bị tag thay đổi âm thầm. */
    private static final String POSTGRES_IMAGE = "pgvector/pgvector:pg17@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d";

    /** Container riêng, tự hủy khi đóng context; không hỗ trợ URL database bên ngoài hoặc reuse. */
    @Bean(initMethod = "start", destroyMethod = "stop")
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("question_it").withUsername("question_test").withPassword("local-test-only");
    }

    /** Khởi tạo schema cũ, sau đó chạy nguyên file migration đang được quản lý trong Git. */
    @Bean
    DataSource dataSource(PostgreSQLContainer postgres) throws Exception {
        DataSource source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Connection connection = source.getConnection()) {
            execute(connection, new ClassPathResource("question/legacy-question-schema.sql")
                    .getContentAsString(StandardCharsets.UTF_8));
            execute(connection, migration());
        }
        return source;
    }

    /** Đọc SQL từ repository để test thất bại nếu file triển khai thực tế không hợp lệ. */
    static String migration() throws Exception {
        return Files.readString(projectFile("docs/migrations/20261005_question_crud.sql"), StandardCharsets.UTF_8);
    }

    /** Xác định đường dẫn theo Maven hoặc thư mục làm việc của IDE, không dùng đường dẫn máy cá nhân. */
    static Path projectFile(String relative) {
        return Path.of(System.getProperty("test.project.root", ".")).resolve(relative);
    }

    /** Gửi nguyên script cho PostgreSQL để giữ đúng transaction và các khối DO $$ của migration. */
    static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Hibernate chỉ xác minh mapping; không tự tạo hoặc sửa schema thay cho migration. */
    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(Question.class.getName(), Subject.class.getName(),
                User.class.getName(), Role.class.getName(), Rubric.class.getName(), RubricCriterion.class.getName(),
                LecturerSubjectAssignment.class.getName(), QuestionAuditLog.class.getName(), InvalidatedToken.class.getName()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", "aives"));
        return factory;
    }

    /** Mỗi request và mỗi luồng sử dụng transaction PostgreSQL riêng. */
    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }

    /** Bật validation trên service giống ứng dụng thật. */
    @Bean
    static MethodValidationPostProcessor methodValidationPostProcessor() {
        return new MethodValidationPostProcessor();
    }
}
