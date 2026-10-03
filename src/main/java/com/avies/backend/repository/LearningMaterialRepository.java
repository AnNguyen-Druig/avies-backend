package com.avies.backend.repository;

import com.avies.backend.entity.LearningMaterial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LearningMaterialRepository extends JpaRepository<LearningMaterial, Long> {
    List<LearningMaterial> findBySubjectId(Long subjectId);
    List<LearningMaterial> findByUploadedById(Long userId);
}
