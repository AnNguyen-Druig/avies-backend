package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Phân công một giảng viên quản lý câu hỏi trong một môn học.
 */
@Entity
@Table(name = "lecturer_subject_assignments", schema = "aives",
        uniqueConstraints = @UniqueConstraint(columnNames = {"lecturer_id", "subject_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LecturerSubjectAssignment {
    /**
     * Định danh bản phân công.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Giảng viên được phân công; role vẫn được kiểm tra khi gọi API.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecturer_id", nullable = false)
    private User lecturer;

    /**
     * Môn học thuộc phạm vi phân công.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id", nullable = false)
    private Subject subject;
}
