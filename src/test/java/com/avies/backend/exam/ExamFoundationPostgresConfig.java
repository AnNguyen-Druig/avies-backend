package com.avies.backend.exam;

import com.avies.backend.entity.*;
import com.avies.backend.entity.Role;
import com.avies.backend.repository.ExamSessionRepository;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** Context persistence thuần trên PostgreSQL tạm; không đọc application.yaml, secrets hoặc gọi AI. */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = ExamSessionRepository.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = ".*(LearningMaterial|InvalidatedToken|RefreshToken|LecturerSubjectAssignment|QuestionAuditLog|RoleRepository|UserRepository|SubjectRepository|RubricRepository).*"))
public class ExamFoundationPostgresConfig {
    /** Cùng image PostgreSQL 17/pgvector đã khóa digest với bộ hồi quy hiện có. */
    private static final String IMAGE = "pgvector/pgvector:pg17@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d";

    /** Tạo container riêng, không dùng lại hay nhận URL của database bên ngoài. */
    static PostgreSQLContainer newPostgres() {
        return new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("feature2_it").withUsername("exam_test").withPassword("local-test-only");
    }

    /** Container tự khởi động và tự dọn khi context kết thúc. */
    @Bean(initMethod = "start", destroyMethod = "stop")
    PostgreSQLContainer postgres() {
        return newPostgres();
    }

    /** Nâng cấp schema trước Feature 2 có dữ liệu thật trong fixture, rồi mới validate entity. */
    @Bean
    DataSource dataSource(PostgreSQLContainer postgres) throws Exception {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Connection connection = source.getConnection()) {
            legacySchema(connection);
            execute(connection, projectSql("docs/migrations/20261016_feature2_exam_foundation.sql"));
        }
        return source;
    }

    /** Tái sử dụng fixture Question đã có, thay liên kết thi tối giản bằng baseline đủ 20 constraint. */
    static void legacySchema(Connection connection) throws Exception {
        String question = new ClassPathResource("question/legacy-question-schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        question = question.replaceFirst("(?s)CREATE TABLE aives\\.student_exam_questions \\(.*?\\);", "");
        execute(connection, question);
        execute(connection, projectSql("docs/migrations/20261005_question_crud.sql"));
        execute(connection, new ClassPathResource("exam/legacy-exam-schema.sql").getContentAsString(StandardCharsets.UTF_8));
    }

    /** Đọc nguyên script quản lý trong repository; không dùng bản SQL rút gọn thay migration. */
    static String projectSql(String relative) throws Exception {
        return Files.readString(Path.of(System.getProperty("test.project.root", ".")).resolve(relative), StandardCharsets.UTF_8);
    }

    /** Gửi nguyên script để PostgreSQL xử lý đúng transaction và khối DO. */
    static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Hibernate chỉ validate mapping sau migration, không tạo/sửa schema. */
    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(User.class.getName(), Role.class.getName(),
                Subject.class.getName(), Question.class.getName(), Rubric.class.getName(), RubricCriterion.class.getName(),
                ExamSession.class.getName(), StudentExam.class.getName(), StudentExamQuestion.class.getName()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", "aives"));
        return factory;
    }

    /** Transaction thật để kiểm tra FK, UNIQUE và rollback mà không dùng database dùng chung. */
    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }
}
