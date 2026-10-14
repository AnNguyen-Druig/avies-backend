package com.avies.backend.repository;

import com.avies.backend.entity.LecturerSubjectAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Collection;
import java.util.List;

/**
 * Truy vấn phạm vi môn học được phân công cho giảng viên.
 */
public interface LecturerSubjectAssignmentRepository extends JpaRepository<LecturerSubjectAssignment, Long> {
    /**
     * Kiểm tra cặp giảng viên và môn học có được phân công hay không.
     */
    boolean existsByLecturerIdAndSubjectId(Long lecturerId, Long subjectId);

    /** Tải đúng các phân công trong lô yêu cầu sau khi đã khóa tài khoản đích. */
    @EntityGraph(attributePaths = "subject")
    List<LecturerSubjectAssignment> findByLecturerIdAndSubjectIdIn(Long lecturerId, Collection<Long> subjectIds);

    /** Admin xem toàn bộ phân công hoặc lọc một giảng viên; tải quan hệ để tránh N+1. */
    @EntityGraph(attributePaths = {"lecturer", "subject"})
    @Query("select a from LecturerSubjectAssignment a where (:lecturerId is null or a.lecturer.id = :lecturerId)")
    Page<LecturerSubjectAssignment> findAssignments(@Param("lecturerId") Long lecturerId, Pageable pageable);

    /** Giới hạn ngay tại database để giảng viên chỉ đọc được môn được phân cho mình. */
    @EntityGraph(attributePaths = "subject")
    Page<LecturerSubjectAssignment> findByLecturerId(Long lecturerId, Pageable pageable);
}
