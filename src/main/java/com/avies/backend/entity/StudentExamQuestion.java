package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;


/** Liên kết câu hỏi ngân hàng với bài thi sinh viên; không xóa lan sang câu hỏi gốc. */
@Entity
@Table(name = "student_exam_questions", schema = "aives",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_student_exam_question", columnNames = {"student_exam_id", "question_id"}),
            @UniqueConstraint(name = "uq_student_exam_question_order", columnNames = {"student_exam_id", "question_order"})
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentExamQuestion {
    /** Định danh lần gán câu hỏi. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Bài thi sinh viên nhận câu hỏi. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_exam_id", nullable = false)
    private StudentExam studentExam;

    /** Câu hỏi gốc được bảo vệ bởi khóa ngoại ON DELETE RESTRICT. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    /** Thứ tự câu hỏi dương và duy nhất trong một bài thi. */
    @Column(name = "question_order", nullable = false)
    private Integer questionOrder;

    /** Nội dung nguyên bản khi gán câu; không đọc lại ngân hàng để dựng đề thi cũ. */
    @Column(name = "snapshot_content", columnDefinition = "TEXT", updatable = false)
    private String snapshotContent;

    /** Đáp án tham khảo tại thời điểm chọn, có thể null theo nghiệp vụ câu hỏi. */
    @Column(name = "snapshot_reference_answer", columnDefinition = "TEXT", updatable = false)
    private String snapshotReferenceAnswer;

    /** Bloom được giữ nguyên theo phiên bản câu đã chọn. */
    @Column(name = "snapshot_bloom_level", length = 20, updatable = false)
    private String snapshotBloomLevel;

    /** Giới hạn trả lời gốc; null nghĩa là câu chưa cấu hình giới hạn riêng. */
    @Column(name = "snapshot_answer_time_limit_seconds", updatable = false)
    private Integer snapshotAnswerTimeLimitSeconds;

    /** Version nguồn để truy xuất, không dùng thay cho bản chụp nội dung. */
    @Column(name = "snapshot_question_version", updatable = false)
    private Long snapshotQuestionVersion;

    /** Mốc chốt toàn bộ thông tin câu; null được giữ lại để nhận diện dữ liệu legacy. */
    @Column(name = "snapshot_captured_at", updatable = false)
    private OffsetDateTime snapshotCapturedAt;
}
