package com.avies.backend.exam;

import com.avies.backend.controller.*;
import com.avies.backend.service.impl.StudentExamServiceImpl;
import com.avies.backend.service.support.ExamSlotPlanner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Tái sử dụng schema/migration và datasource Docker của CRUD, không nạp application.yaml hoặc .env. */
@Configuration
@Import({ExamSessionCrudPostgresConfig.class, EligibleStudentController.class, StudentAssignmentController.class,
        StudentExamController.class, StudentExamServiceImpl.class, ExamSlotPlanner.class})
public class StudentAssignmentPostgresConfig { }
