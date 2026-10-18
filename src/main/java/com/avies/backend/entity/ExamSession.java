package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import com.avies.backend.entity.enums.ExamSessionStatus;
import org.hibernate.annotations.CreationTimestamp;
import java.time.OffsetDateTime;

/** Phiên thi vấn đáp; lịch dự kiến có thể trống đối với dữ liệu trước Feature 2. */
@Entity
@Table(name = "exam_sessions", schema = "aives")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamSession {
    /** Định danh phiên thi do PostgreSQL sinh. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Giảng viên sở hữu phiên thi; quyền và vai trò do service kiểm tra ở giai đoạn sau. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecturer_id", nullable = false)
    private User lecturer;

    /** Môn học của phiên thi. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id", nullable = false)
    private Subject subject;

    /** Tiêu đề phiên thi. */
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** Trạng thái phiên thi, tương ứng đúng ba giá trị trong database. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ExamSessionStatus status = ExamSessionStatus.UPCOMING;

    /** Số câu hỏi chính tối đa cho mỗi sinh viên. */
    @Column(name = "max_main_questions", nullable = false)
    @Builder.Default
    private Integer maxMainQuestions = 3;

    /** Số câu hỏi phụ tối đa, cho phép bằng không. */
    @Column(name = "max_follow_ups", nullable = false)
    @Builder.Default
    private Integer maxFollowUps = 2;

    /** Thời điểm tạo; Hibernate gán khi insert và không cập nhật lại. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Thời điểm bắt đầu dự kiến; null nếu lịch cũ chưa được xác định. */
    @Column(name = "scheduled_start_at")
    private OffsetDateTime scheduledStartAt;

    /** Thời điểm kết thúc dự kiến, phải sau bắt đầu khi có lịch. */
    @Column(name = "scheduled_end_at")
    private OffsetDateTime scheduledEndAt;

    /** Số phút dự kiến cho mỗi sinh viên, phải dương khi được cung cấp. */
    @Column(name = "slot_duration_minutes")
    private Integer slotDurationMinutes;
}
