package com.avies.backend.repository;

import com.avies.backend.entity.Rubric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface RubricRepository extends JpaRepository<Rubric, Long> {
    /** FOR UPDATE rubric chặn insert criterion qua FK KEY SHARE trong lúc kiểm tra tổng điểm. */
    @Query(value = "SELECT id FROM aives.rubrics WHERE question_id IN (:questionIds) ORDER BY id FOR UPDATE NOWAIT", nativeQuery = true)
    List<Long> lockForQuestionSelection(@Param("questionIds") Collection<Long> questionIds);

    /** Giữ nội dung/điểm của mọi criterion hiện có; khóa rubric bên trên bảo vệ cả membership. */
    @Query(value = "SELECT id FROM aives.rubric_criteria WHERE rubric_id IN (:rubricIds) ORDER BY id FOR SHARE NOWAIT", nativeQuery = true)
    List<Long> lockCriteriaForSelection(@Param("rubricIds") Collection<Long> rubricIds);

    Optional<Rubric> findByQuestionId(Long questionId);
}
