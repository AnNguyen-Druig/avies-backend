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
    /** Batch nhỏ cho tác vụ hết giờ; service phải khóa và kiểm tra lại trước khi ghi, không tin danh sách ứng viên. */
    @Query(value="SELECT id FROM aives.student_exams WHERE status='IN_PROGRESS' AND scheduled_end_at<=:now ORDER BY scheduled_end_at,id LIMIT 50",nativeQuery=true)
    List<Long> findOverdueAttempts(@Param("now") java.time.OffsetDateTime now);

    /** Một chuyến DB giữ đúng chuỗi khóa oral; mỗi CTE phụ thuộc khóa trước, không bỏ khóa vì giảm query. */
    @Query(value = """
            WITH owner_lock AS MATERIALIZED (SELECT id FROM aives.users WHERE id=:ownerId FOR UPDATE NOWAIT),
            subject_lock AS MATERIALIZED (SELECT s.id FROM aives.subjects s,owner_lock WHERE s.id=:subjectId FOR SHARE OF s),
            session_lock AS MATERIALIZED (SELECT s.id FROM aives.exam_sessions s,owner_lock o,subject_lock u
                WHERE s.id=:sessionId AND s.lecturer_id=o.id AND s.subject_id=u.id FOR UPDATE OF s NOWAIT),
            student_lock AS MATERIALIZED (SELECT u.id FROM aives.users u,session_lock WHERE u.id=:studentId FOR SHARE OF u NOWAIT),
            exam_lock AS MATERIALIZED (SELECT e.id FROM aives.student_exams e,session_lock s,student_lock u
                WHERE e.id=:examId AND e.session_id=s.id AND e.student_id=u.id FOR UPDATE OF e NOWAIT),
            question_lock AS MATERIALIZED (SELECT q.id FROM aives.student_exam_questions q,exam_lock e
                WHERE q.student_exam_id=e.id ORDER BY q.id FOR UPDATE OF q NOWAIT)
            SELECT e.id,(SELECT count(*) FROM question_lock) FROM exam_lock e
            """, nativeQuery = true)
    List<Object[]> lockOralHierarchy(@Param("examId") Long examId,@Param("sessionId") Long sessionId,
            @Param("ownerId") Long ownerId,@Param("subjectId") Long subjectId,@Param("studentId") Long studentId);

    /** Sau khóa parent, khóa toàn bộ lượt theo ID để Close tuần tự với Start/Finish và insert câu. */
    @Query(value = "SELECT id FROM aives.student_exams WHERE session_id=:id ORDER BY id FOR UPDATE NOWAIT", nativeQuery = true)
    List<Long> lockForSessionClosure(@Param("id") Long id);

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
