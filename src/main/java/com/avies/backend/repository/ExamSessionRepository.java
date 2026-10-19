package com.avies.backend.repository;

import com.avies.backend.entity.ExamSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.avies.backend.entity.enums.ExamSessionStatus;
import java.util.Optional;

/** Truy cập phiên thi; phân quyền giảng viên–môn thuộc service trong các bước tiếp theo. */
public interface ExamSessionRepository extends JpaRepository<ExamSession, Long> {
    /** Liệt kê phiên của giảng viên theo ID ổn định, có phân trang. */
    Page<ExamSession> findByLecturerIdOrderByIdAsc(Long lecturerId, Pageable pageable);

    /** Liệt kê phiên trong một môn theo ID ổn định, có phân trang. */
    Page<ExamSession> findBySubjectIdOrderByIdAsc(Long subjectId, Pageable pageable);

    /** Kiểm tra quyền sở hữu mà không tải toàn bộ phiên. */
    boolean existsByIdAndLecturerId(Long id, Long lecturerId);

    /** Nạp các quan hệ đơn cần cho DTO; không tải danh sách bài thi hoặc điểm sinh viên. */
    @EntityGraph(attributePaths = {"subject", "lecturer"})
    @Query("select e from ExamSession e where e.id = :id")
    Optional<ExamSession> findForDisplay(@Param("id") Long id);

    /** Lọc chủ sở hữu và bộ lọc tại database trước phân trang; admin truyền ownerId=null. */
    @EntityGraph(attributePaths = {"subject", "lecturer"})
    @Query("select e from ExamSession e where (:ownerId is null or e.lecturer.id = :ownerId) "
            + "and (:subjectId is null or e.subject.id = :subjectId) "
            + "and (:status is null or e.status = :status) order by e.id desc")
    Page<ExamSession> searchAccessible(@Param("ownerId") Long ownerId, @Param("subjectId") Long subjectId,
                                      @Param("status") ExamSessionStatus status, Pageable pageable);

    /** FOR UPDATE đủ mạnh để chặn FK KEY SHARE của insert StudentExam; NOWAIT trả xung đột ngay. */
    @Query(value = "SELECT id FROM aives.exam_sessions WHERE id = :id FOR UPDATE NOWAIT", nativeQuery = true)
    Optional<Long> lockForManagement(@Param("id") Long id);
}
