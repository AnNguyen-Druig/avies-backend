package com.avies.backend.question;

import com.avies.backend.entity.*;
import com.avies.backend.entity.Role;
import com.avies.backend.repository.QuestionRepository;
import com.avies.backend.service.impl.QuestionServiceImpl;
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
import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

/** Cấu hình test độc lập: chỉ dùng H2 trong bộ nhớ, không đọc application.yaml hay gọi AI. */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = QuestionRepository.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = ".*(LearningMaterial|InvalidatedToken|RefreshToken).*"))
@Import(QuestionServiceImpl.class)
public class QuestionPersistenceTestConfig {
    /** Tạo nguồn dữ liệu tạm chỉ tồn tại trong tiến trình test. */
    @Bean
    DataSource dataSource() {
        String databaseName = "question-tests-" + UUID.randomUUID();
        return new DriverManagerDataSource("jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS aives", "sa", "");
    }

    /** Chỉ ánh xạ những entity của Question để không kéo pgvector vào H2. */
    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(
                Question.class.getName(), Subject.class.getName(), User.class.getName(), Role.class.getName(),
                Rubric.class.getName(), RubricCriterion.class.getName(),
                LecturerSubjectAssignment.class.getName(), QuestionAuditLog.class.getName()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.show_sql", Boolean.getBoolean("question.tests.sql")));
        return factory;
    }

    /** Dùng transaction thật trên H2 để kiểm tra cập nhật, rollback và khóa ngoại. */
    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }

    /** Bật validation trên hợp đồng service giống môi trường ứng dụng. */
    @Bean
    static MethodValidationPostProcessor methodValidationPostProcessor() {
        return new MethodValidationPostProcessor();
    }
}
