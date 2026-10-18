package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;


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
}
