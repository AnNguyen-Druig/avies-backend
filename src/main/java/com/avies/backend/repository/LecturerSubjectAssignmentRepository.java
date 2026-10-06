package com.avies.backend.repository;

import com.avies.backend.entity.LecturerSubjectAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Truy vấn phạm vi môn học được phân công cho giảng viên.
 */
public interface LecturerSubjectAssignmentRepository extends JpaRepository<LecturerSubjectAssignment, Long> {
    /**
     * Kiểm tra cặp giảng viên và môn học có được phân công hay không.
     */
    boolean existsByLecturerIdAndSubjectId(Long lecturerId, Long subjectId);
}
