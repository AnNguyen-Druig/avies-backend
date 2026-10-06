package com.avies.backend.repository;

import com.avies.backend.entity.QuestionAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lưu sự kiện câu hỏi trong cùng giao dịch với thay đổi nghiệp vụ.
 */
public interface QuestionAuditLogRepository extends JpaRepository<QuestionAuditLog, Long> {
}
