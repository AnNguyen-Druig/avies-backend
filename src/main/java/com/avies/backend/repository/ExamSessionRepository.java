package com.avies.backend.repository;

import com.avies.backend.entity.ExamSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Truy cập phiên thi; phân quyền giảng viên–môn thuộc service trong các bước tiếp theo. */
public interface ExamSessionRepository extends JpaRepository<ExamSession, Long> {
    /** Liệt kê phiên của giảng viên theo ID ổn định, có phân trang. */
    Page<ExamSession> findByLecturerIdOrderByIdAsc(Long lecturerId, Pageable pageable);

    /** Liệt kê phiên trong một môn theo ID ổn định, có phân trang. */
    Page<ExamSession> findBySubjectIdOrderByIdAsc(Long subjectId, Pageable pageable);

    /** Kiểm tra quyền sở hữu mà không tải toàn bộ phiên. */
    boolean existsByIdAndLecturerId(Long id, Long lecturerId);
}
