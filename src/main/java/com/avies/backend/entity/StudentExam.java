package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import com.avies.backend.entity.enums.StudentExamStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Bài thi của một sinh viên trong phiên; phân biệt lịch dự kiến với thời gian thi thực tế. */
@Entity
@Table(name = "student_exams", schema = "aives",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_exam_session_student", columnNames = {"session_id", "student_id"}),
            @UniqueConstraint(name = "uq_student_exam_slot_order", columnNames = {"session_id", "slot_order"})
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentExam {
    /** Định danh bài thi của sinh viên. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Phiên thi chứa bài thi; không cascade thay đổi hoặc xóa phiên. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ExamSession session;

    /** Sinh viên được gán bài thi; service sẽ kiểm tra vai trò. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    /** Trạng thái thực hiện bài thi theo các giá trị database hiện có. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private StudentExamStatus status = StudentExamStatus.ASSIGNED;

    /** Điểm AI gợi ý, không thay thế điểm giảng viên chốt. */
    @Column(name = "ai_suggested_score", precision = 5, scale = 2)
    private BigDecimal aiSuggestedScore;

    /** Điểm cuối do giảng viên quyết định. */
    @Column(name = "final_score", precision = 5, scale = 2)
    private BigDecimal finalScore;

    /** Nhận xét chấm điểm AI, có thể chưa có. */
    @Column(name = "ai_feedback", columnDefinition = "TEXT")
    private String aiFeedback;

    /** Đường dẫn bản ghi âm nếu được lưu. */
    @Column(name = "audio_record_url", length = 500)
    private String audioRecordUrl;

    /** Thời điểm sinh viên thực sự bắt đầu thi. */
    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    /** Thời điểm thực sự hoàn tất, không phải thời điểm kết thúc dự kiến. */
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** Thứ tự thi trong phiên; null dành cho dữ liệu cũ chưa xếp lịch. */
    @Column(name = "slot_order")
    private Integer slotOrder;

    /** Thời điểm thi dự kiến của sinh viên. */
    @Column(name = "scheduled_start_at")
    private OffsetDateTime scheduledStartAt;

    /** Thời điểm kết thúc dự kiến của sinh viên. */
    @Column(name = "scheduled_end_at")
    private OffsetDateTime scheduledEndAt;
}
