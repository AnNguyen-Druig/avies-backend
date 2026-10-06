package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Nhật ký bất biến của thao tác câu hỏi, được giữ lại cả khi bản nháp bị xóa.
 */
@Entity
@Table(name = "question_audit_logs", schema = "aives")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionAuditLog {
    /**
     * Khóa định danh của sự kiện.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * ID câu hỏi dạng giá trị để nhật ký không bị xóa theo entity.
     */
    @Column(name = "question_id", nullable = false)
    private Long questionId;

    /**
     * Người thực hiện thao tác.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    /**
     * Loại thao tác: CREATED, UPDATED hoặc DELETED trong CRUD hiện tại.
     */
    @Column(nullable = false, length = 30)
    private String action;

    /**
     * Thời điểm thao tác được ghi nhận trong cùng transaction.
     */
    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;
}
