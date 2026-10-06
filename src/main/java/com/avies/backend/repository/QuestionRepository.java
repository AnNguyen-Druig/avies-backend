package com.avies.backend.repository;

import com.avies.backend.entity.Question;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Truy cập câu hỏi, hỗ trợ lọc có phân quyền và kiểm tra tham chiếu bài thi.
 */
public interface QuestionRepository extends JpaRepository<Question, Long>, JpaSpecificationExecutor<Question> {
    /**
     * Nạp các quan hệ đơn cần cho DTO, không fetch collection khi phân trang.
     */
    @Override
    @EntityGraph(attributePaths = {"subject", "createdBy", "updatedBy", "rubric"})
    Page<Question> findAll(Specification<Question> specification, Pageable pageable);

    /**
     * Nạp chi tiết cùng các quan hệ cần hiển thị.
     */
    @Override
    @EntityGraph(attributePaths = {"subject", "createdBy", "updatedBy", "rubric"})
    Optional<Question> findById(Long id);

    /**
     * Khóa câu hỏi khi ghi để tuần tự hóa cập nhật và xóa đồng thời.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Question q where q.id = :id")
    Optional<Question> findForUpdate(@Param("id") Long id);

    /**
     * Kiểm tra câu hỏi đã được đưa vào bài thi mà không cần tạo entity bài thi trong đợt này.
     */
    @Query(value = "select count(*) from aives.student_exam_questions where question_id = :id", nativeQuery = true)
    long countExamReferences(@Param("id") Long id);
}
