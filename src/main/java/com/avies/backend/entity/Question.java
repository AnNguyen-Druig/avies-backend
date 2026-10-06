package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import com.avies.backend.entity.enums.*;
import org.hibernate.annotations.BatchSize;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Câu hỏi của một môn học, có chủ sở hữu, nhãn chủ đề và vòng đời xét duyệt riêng.
 */
@Entity
@Table(name = "questions", schema = "aives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Question {

    /**
     * Khóa định danh do cơ sở dữ liệu sinh.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    /**
     * Môn học cố định của câu hỏi.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id", nullable = false)
    Subject subject;

    /**
     * Người tạo; không được thay đổi qua API cập nhật nội dung.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    User createdBy;

    /**
     * Nội dung văn bản bắt buộc.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    String content;

    /**
     * Mức Bloom thuộc phạm vi MVP; bản nháp có thể chưa có.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "bloom_level")
    BloomLevel bloomLevel;

    /**
     * Cờ tương thích schema cũ, được đồng bộ từ nguồn tạo.
     */
    @Column(name = "is_ai_generated")
    Boolean isAiGenerated;

    /**
     * Cờ tương thích schema cũ, được đồng bộ từ trạng thái duyệt.
     */
    @Column(name = "is_approved")
    Boolean isApproved;

    /**
     * Rubric riêng; chỉ được xóa theo câu hỏi khi nghiệp vụ cho phép.
     */
    @OneToOne(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    Rubric rubric;

    /**
     * Thời điểm tạo do service gán; không cập nhật lại.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    OffsetDateTime createdAt;

    /**
     * Đáp án tham khảo dành cho giảng viên.
     */
    @Column(name = "reference_answer", columnDefinition = "TEXT")
    String referenceAnswer;

    /**
     * Tập nhãn dạng chuỗi, không phải danh mục chủ đề độc lập.
     */
    @ElementCollection
    @CollectionTable(name = "question_topics", schema = "aives",
            joinColumns = @JoinColumn(name = "question_id"))
    @Column(name = "topic", nullable = false, length = 100)
    @BatchSize(size = 100)
    @Builder.Default
    Set<String> topics = new LinkedHashSet<>();

    /**
     * Thời gian dự kiến trả lời câu chính, tính bằng giây.
     */
    @Column(name = "answer_time_limit_seconds")
    Integer answerTimeLimitSeconds;

    /**
     * Nguồn tạo; null chỉ dành cho dữ liệu cũ chưa xác minh.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 20)
    QuestionSourceType sourceType;

    /**
     * Trạng thái xét duyệt; null khóa dữ liệu cũ chưa phân loại khỏi luồng chỉnh sửa.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    QuestionStatus status;

    /**
     * Người cập nhật gần nhất.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    User updatedBy;

    /**
     * Thời điểm cập nhật gần nhất.
     */
    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;

    /**
     * Thời điểm lưu trữ; null nghĩa câu hỏi còn hoạt động.
     */
    @Column(name = "archived_at")
    OffsetDateTime archivedAt;

    /**
     * Khóa lạc quan ngăn ghi đè dữ liệu đã bị thay đổi đồng thời.
     */
    @Version
    @Column(nullable = false)
    Long version;
}
