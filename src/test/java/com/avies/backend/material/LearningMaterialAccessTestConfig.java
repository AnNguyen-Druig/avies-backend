package com.avies.backend.material;

import com.avies.backend.config.CorsProperties;
import com.avies.backend.config.SecurityConfig;
import com.avies.backend.controller.LearningMaterialController;
import com.avies.backend.controller.LecturerSubjectAssignmentController;
import com.avies.backend.entity.LearningMaterial;
import com.avies.backend.entity.LecturerSubjectAssignment;
import com.avies.backend.entity.Role;
import com.avies.backend.entity.Subject;
import com.avies.backend.entity.User;
import com.avies.backend.exception.exceptions.AssignmentExceptionHandler;
import com.avies.backend.exception.GlobalExceptionHandler;
import com.avies.backend.repository.LearningMaterialRepository;
import com.avies.backend.service.impl.LearningMaterialServiceImpl;
import com.avies.backend.service.impl.LecturerSubjectAssignmentServiceImpl;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

/** Cấu hình test tài liệu trên H2 cô lập; không đọc application.yaml hoặc truy cập Supabase. */
@Configuration
@EnableWebMvc
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = LearningMaterialRepository.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = ".*(Question|Rubric|LearningMaterialChunk|InvalidatedToken|RefreshToken|ExamSession|StudentExam).*"))
@Import({LearningMaterialController.class, LearningMaterialServiceImpl.class,
        com.avies.backend.service.support.SubjectAccessHelper.class,
        LecturerSubjectAssignmentController.class, LecturerSubjectAssignmentServiceImpl.class,
        GlobalExceptionHandler.class, AssignmentExceptionHandler.class, SecurityConfig.class, CorsProperties.class})
public class LearningMaterialAccessTestConfig {
    /** Tạo database H2 riêng cho context test, không sử dụng URL hay mật khẩu thật. */
    @Bean
    DataSource dataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:material-access-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS aives", "sa", "");
    }

    /** Chỉ ánh xạ entity phục vụ phân công và metadata tài liệu, không mô phỏng pgvector. */
    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(User.class.getName(), Role.class.getName(),
                Subject.class.getName(), LecturerSubjectAssignment.class.getName(), LearningMaterial.class.getName()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        return factory;
    }

    /** Mỗi request HTTP được commit/rollback độc lập để kiểm tra thu hồi có hiệu lực ở request tiếp theo. */
    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }
}
