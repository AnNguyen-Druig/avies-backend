package com.avies.backend.repository;

import com.avies.backend.entity.StudentExamQuestion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

/** Truy cập câu đã gán vào bài thi; chỉ cung cấp dữ liệu cho bước chọn câu sau này. */
public interface StudentExamQuestionRepository extends JpaRepository<StudentExamQuestion, Long> {
    /** Khóa các liên kết đã có sau parent/child; không cho raw UPDATE/DELETE đổi bộ trong lúc retry. */
    @Query(value = "SELECT id FROM aives.student_exam_questions WHERE student_exam_id=:id ORDER BY id FOR UPDATE NOWAIT", nativeQuery = true)
    List<Long> lockPersistedSet(@Param("id") Long id);

    /** Đọc toàn bộ bộ chính theo thứ tự đã lưu, không phân trang khiến mất kiểm tra đủ N. */
    List<StudentExamQuestion> findByStudentExamIdOrderByQuestionOrderAscIdAsc(Long studentExamId);

    /** Lịch sử chỉ trong cùng phiên, không lấy nội dung hoặc rubric của câu đã dùng. */
    @Query("select a from StudentExamQuestion a join fetch a.studentExam e where e.session.id=:sessionId "
            + "and e.id<>:studentExamId order by a.id")
    List<StudentExamQuestion> findSelectionHistory(@Param("sessionId") Long sessionId, @Param("studentExamId") Long studentExamId);

    /** Có câu đã gán thì không thu hồi bài, tránh cascade làm mất câu và transcript bên dưới. */
    boolean existsByStudentExamId(Long studentExamId);

    /** Đọc câu đã gán đúng thứ tự thi, có phân trang. */
    Page<StudentExamQuestion> findByStudentExamIdOrderByQuestionOrderAscIdAsc(Long studentExamId, Pageable pageable);

    /** Kiểm tra một câu đã nằm trong bài thi hay chưa. */
    boolean existsByStudentExamIdAndQuestionId(Long studentExamId, Long questionId);

    /** Lấy ID các câu từng được dùng trong một phiên, loại trùng và sắp xếp ổn định. */
    @Query("select distinct a.question.id from StudentExamQuestion a "
            + "where a.studentExam.session.id = :sessionId order by a.question.id")
    Page<Long> findUsedQuestionIdsBySessionId(@Param("sessionId") Long sessionId, Pageable pageable);
}
