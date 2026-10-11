package com.avies.backend.repository;

import com.avies.backend.entity.Rubric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RubricRepository extends JpaRepository<Rubric, Long> {
    Optional<Rubric> findByQuestionId(Long questionId);
}
