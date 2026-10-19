package com.avies.backend.repository;

import com.avies.backend.entity.StudentExam;
import com.avies.backend.entity.enums.StudentExamStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

/** Truy cập bài thi sinh viên; không thực hiện thuật toán xếp lịch hay kiểm tra phân công môn. */
public interface StudentExamRepository extends JpaRepository<StudentExam, Long> {
    /** Snapshot lịch dưới khóa parent; nạp student để phản hồi batch không phát sinh N+1. */
    @EntityGraph(attributePaths = "student")
    List<StudentExam> findBySessionIdOrderByIdAsc(Long sessionId);

    /** Scope phiên đã được service kiểm tra trước query/count; legacy slot null xếp cuối. */
    @EntityGraph(attributePaths = "student")
    @Query("select e from StudentExam e where e.session.id = :sessionId "
            + "order by case when e.slotOrder is null then 1 else 0 end, e.slotOrder, e.id")
    Page<StudentExam> searchAssignments(@Param("sessionId") Long sessionId, Pageable pageable);

    /** Lọc ID từ principal trước phân trang; không cho client chọn studentId người khác. */
    @EntityGraph(attributePaths = {"session", "session.subject"})
    @Query("select e from StudentExam e where e.student.id = :studentId order by e.id desc")
    Page<StudentExam> searchMine(@Param("studentId") Long studentId, Pageable pageable);

    /** Chỉ đọc parent ID để định tuyến khóa, chưa khóa child trước parent. */
    @Query("select e.session.id from StudentExam e where e.id = :id")
    Optional<Long> findSessionIdForRemoval(@Param("id") Long id);

    /** Khóa child mạnh để chặn insert StudentExamQuestion bằng FK trong lúc kiểm tra và xóa. */
    @Query(value = "SELECT id FROM aives.student_exams WHERE id = :id FOR UPDATE NOWAIT", nativeQuery = true)
    Optional<Long> lockForRemoval(@Param("id") Long id);

    /** Lấy sinh viên trong phiên: lịch có thứ tự trước, bản ghi cũ chưa xếp lịch sau, ID phá hòa. */
    @Query("select e from StudentExam e where e.session.id = :sessionId "
            + "order by case when e.slotOrder is null then 1 else 0 end, e.slotOrder, e.id")
    Page<StudentExam> findScheduledBySessionId(@Param("sessionId") Long sessionId, Pageable pageable);

    /** Lấy các bài thi của sinh viên theo ID ổn định, có phân trang. */
    Page<StudentExam> findByStudentIdOrderByIdAsc(Long studentId, Pageable pageable);

    /** Kiểm tra sinh viên đã được gán vào phiên để service xử lý yêu cầu lặp sau này. */
    boolean existsBySessionIdAndStudentId(Long sessionId, Long studentId);

    /** Hỗ trợ kiểm tra bài đang thực hiện bằng trạng thái truyền vào, chưa thêm quy tắc chuyển trạng thái. */
    boolean existsByStudentIdAndStatus(Long studentId, StudentExamStatus status);

    /** Không cho xóa phiên đã có bài thi, kể cả bài mới ASSIGNED. */
    boolean existsBySessionId(Long sessionId);

    /** Dấu hiệu bài đã bắt đầu/hoàn tất, kể cả dữ liệu trạng thái và timestamp không đồng bộ. */
    @Query("select case when count(e) > 0 then true else false end from StudentExam e "
            + "where e.session.id = :sessionId and (e.status <> :notStarted "
            + "or e.startedAt is not null or e.completedAt is not null)")
    boolean hasStartedExam(@Param("sessionId") Long sessionId, @Param("notStarted") StudentExamStatus notStarted);
}
