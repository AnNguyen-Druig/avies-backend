package com.avies.backend.repository;

import com.avies.backend.entity.Rubric;
import com.avies.backend.dto.projection.RubricSnapshotRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface RubricRepository extends JpaRepository<Rubric, Long> {
    /** Đọc scalar đầy đủ sau khóa rubric/criteria để tạo snapshot độc lập với cache JPA. */
    @Query(value = """
            SELECT r.id AS "rubricId", r.subject_id AS "subjectId", r.title AS "title",
                   r.description AS "description", r.updated_at AS "updatedAt",
                   c.id AS "criterionId", c.criterion_text AS "criterionText",
                   c.point_weight AS "pointWeight", c.is_mandatory AS "mandatory", c.display_order AS "displayOrder"
            FROM aives.rubrics r LEFT JOIN aives.rubric_criteria c ON c.rubric_id=r.id
            WHERE r.subject_id=:subjectId ORDER BY c.display_order,c.id
            """, nativeQuery = true)
    List<RubricSnapshotRow> readSnapshotRows(@Param("subjectId") Long subjectId);

    /** FOR UPDATE rubric chặn insert criterion qua FK KEY SHARE trong lúc kiểm tra tổng điểm. */
    @Query(value = "SELECT r.id FROM aives.rubrics r WHERE EXISTS (SELECT 1 FROM aives.questions q WHERE q.subject_id=r.subject_id AND q.id IN (:questionIds)) ORDER BY r.id FOR UPDATE NOWAIT", nativeQuery = true)
    List<Long> lockForQuestionSelection(@Param("questionIds") Collection<Long> questionIds);

    /** FOR UPDATE rubric theo subject ID. */
    @Query(value = "SELECT id FROM aives.rubrics WHERE subject_id = :subjectId ORDER BY id FOR UPDATE NOWAIT", nativeQuery = true)
    List<Long> lockForSubject(@Param("subjectId") Long subjectId);

    /** Giữ nội dung/điểm của mọi criterion hiện có; khóa rubric bên trên bảo vệ cả membership. */
    @Query(value = "SELECT id FROM aives.rubric_criteria WHERE rubric_id IN (:rubricIds) ORDER BY id FOR SHARE NOWAIT", nativeQuery = true)
    List<Long> lockCriteriaForSelection(@Param("rubricIds") Collection<Long> rubricIds);

    Optional<Rubric> findBySubjectId(Long subjectId);

    boolean existsBySubjectId(Long subjectId);
}
