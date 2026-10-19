package com.avies.backend.repository;

import com.avies.backend.entity.Question;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import com.avies.backend.dto.projection.QuestionSelectionRow;

/**
 * Truy cập câu hỏi, hỗ trợ lọc có phân quyền và kiểm tra tham chiếu bài thi.
 */
public interface QuestionRepository extends JpaRepository<Question, Long>, JpaSpecificationExecutor<Question> {
    /** Scalar graph dùng chung; tránh đọc nhầm entity/rubric đã cache trước khi lấy khóa. */
    String SELECTION_GRAPH = """
            SELECT q.id AS "questionId", q.subject_id AS "subjectId", q.version AS "questionVersion",
                   q.status AS "questionStatus", q.bloom_level AS "bloomLevel",
                   (q.archived_at IS NOT NULL) AS "archived", q.content AS "content",
                   r.id AS "rubricId", c.id AS "criterionId", c.criterion_text AS "criterionText",
                   c.point_weight AS "pointWeight", c.is_mandatory AS "mandatory", c.display_order AS "displayOrder"
            FROM aives.questions q LEFT JOIN aives.rubrics r ON r.question_id=q.id
            LEFT JOIN aives.rubric_criteria c ON c.rubric_id=r.id
            """;

    /** Pool ban đầu đúng môn và trạng thái; rubric/Bloom được kiểm tra đầy đủ ở service support. */
    @Query(value = SELECTION_GRAPH + " WHERE q.subject_id=:subjectId AND q.status='APPROVED' AND q.archived_at IS NULL ORDER BY q.id,c.id", nativeQuery = true)
    List<QuestionSelectionRow> findSelectionPool(@Param("subjectId") Long subjectId);

    /** Đọc mới các câu đã khóa, kể cả khi trạng thái vừa đổi để phát hiện eligibility drift. */
    @Query(value = SELECTION_GRAPH + " WHERE q.id IN (:ids) ORDER BY q.id,c.id", nativeQuery = true)
    List<QuestionSelectionRow> readSelectionRows(@Param("ids") Collection<Long> ids);

    /** SHARE xung đột cả non-key UPDATE của nội dung/status, không chỉ bảo vệ FK delete. */
    @Query(value = "SELECT id FROM aives.questions WHERE id IN (:ids) ORDER BY id FOR SHARE NOWAIT", nativeQuery = true)
    List<Long> lockForSelection(@Param("ids") Collection<Long> ids);

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
