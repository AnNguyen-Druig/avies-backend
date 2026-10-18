package com.avies.backend.exam;

import com.avies.backend.entity.*;
import com.avies.backend.entity.enums.*;
import com.avies.backend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

/** Kiểm tra mapping/query/constraint bằng PostgreSQL thật sau migration từ schema có dữ liệu cũ. */
@SpringJUnitConfig(ExamFoundationPostgresConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class ExamFoundationPostgresIT {
    /** Entity manager thật, mọi dữ liệu test được rollback sau mỗi ca. */
    @PersistenceContext private EntityManager em;
    /** Repository phiên thi cần kiểm tra phân trang và quyền sở hữu. */
    @Autowired private ExamSessionRepository sessions;
    /** Repository bài thi sinh viên cần kiểm tra thứ tự lịch. */
    @Autowired private StudentExamRepository exams;
    /** Repository liên kết câu hỏi cần kiểm tra lịch sử câu đã dùng. */
    @Autowired private StudentExamQuestionRepository links;
    /** Repository cũ phải tiếp tục đếm tham chiếu thi chính xác. */
    @Autowired private QuestionRepository questions;
    /** Datasource chỉ do container trong context cấp. */
    @Autowired private DataSource source;
    /** JDBC tham gia cùng transaction để thử dữ liệu sai trực tiếp tại database. */
    private JdbcTemplate jdbc;
    /** Mốc lịch dự kiến có múi giờ để kiểm tra TIMESTAMPTZ. */
    private final OffsetDateTime scheduled = OffsetDateTime.parse("2026-11-01T09:00:00+07:00");

    /** Tạo JDBC trên đúng datasource cô lập của bộ test. */
    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(source);
    }

    /** Cả ba entity round-trip đầy đủ với FK, enum, số thập phân và thời gian thực tế/dự kiến. */
    @Test
    void persistsAllMappingsAndKeepsAssociationsLazy() {
        ExamSession session = session("Thi vấn đáp");
        session.setScheduledStartAt(scheduled);
        session.setScheduledEndAt(scheduled.plusHours(2));
        session.setSlotDurationMinutes(15);
        sessions.saveAndFlush(session);
        StudentExam exam = studentExam(session, student("new-student"), 1);
        exam.setStatus(StudentExamStatus.IN_PROGRESS);
        exam.setScheduledStartAt(scheduled.plusMinutes(15));
        exam.setScheduledEndAt(scheduled.plusMinutes(30));
        exam.setStartedAt(scheduled.plusMinutes(17));
        exam.setCompletedAt(scheduled.plusMinutes(28));
        exam.setAiSuggestedScore(new BigDecimal("7.25"));
        exam.setFinalScore(new BigDecimal("8.50"));
        exam.setAiFeedback("Phản hồi thử");
        exam.setAudioRecordUrl("demo/audio.wav");
        exams.saveAndFlush(exam);
        StudentExamQuestion link = links.saveAndFlush(StudentExamQuestion.builder()
                .studentExam(exam).question(em.getReference(Question.class, 1L)).questionOrder(1).build());
        em.clear();
        ExamSession reloadedSession = sessions.findById(session.getId()).orElseThrow();
        assertThat(reloadedSession.getStatus()).isEqualTo(ExamSessionStatus.UPCOMING);
        assertThat(reloadedSession.getMaxMainQuestions()).isEqualTo(3);
        assertThat(reloadedSession.getMaxFollowUps()).isEqualTo(2);
        assertThat(reloadedSession.getSlotDurationMinutes()).isEqualTo(15);
        assertThat(reloadedSession.getCreatedAt()).isNotNull();
        assertThat(reloadedSession.getScheduledStartAt().toInstant()).isEqualTo(scheduled.toInstant());
        assertThat(reloadedSession.getScheduledEndAt().toInstant()).isEqualTo(scheduled.plusHours(2).toInstant());
        assertThat(em.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(reloadedSession, "lecturer")).isFalse();
        assertThat(em.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(reloadedSession, "subject")).isFalse();
        StudentExam stored = exams.findById(exam.getId()).orElseThrow();
        assertThat(stored.getSession().getId()).isEqualTo(session.getId());
        assertThat(stored.getStudent().getId()).isEqualTo(exam.getStudent().getId());
        assertThat(stored.getStatus()).isEqualTo(StudentExamStatus.IN_PROGRESS);
        assertThat(stored.getSlotOrder()).isEqualTo(1);
        assertThat(stored.getAiSuggestedScore()).isEqualByComparingTo("7.25");
        assertThat(stored.getFinalScore()).isEqualByComparingTo("8.50");
        assertThat(stored.getAiFeedback()).isEqualTo("Phản hồi thử");
        assertThat(stored.getAudioRecordUrl()).isEqualTo("demo/audio.wav");
        assertThat(stored.getScheduledEndAt().toInstant()).isEqualTo(scheduled.plusMinutes(30).toInstant());
        assertThat(stored.getScheduledStartAt().toInstant()).isEqualTo(scheduled.plusMinutes(15).toInstant());
        assertThat(stored.getStartedAt().toInstant()).isEqualTo(scheduled.plusMinutes(17).toInstant());
        assertThat(stored.getCompletedAt().toInstant()).isEqualTo(scheduled.plusMinutes(28).toInstant());
        StudentExamQuestion storedLink = links.findById(link.getId()).orElseThrow();
        assertThat(storedLink.getStudentExam().getId()).isEqualTo(exam.getId());
        assertThat(storedLink.getQuestion().getId()).isEqualTo(1L);
        assertThat(storedLink.getQuestionOrder()).isEqualTo(1);
    }

    /** Mọi biến thể lịch sai đều bị CHECK chặn, không để SQL UNKNOWN chấp nhận cặp thiếu một đầu. */
    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE aives.exam_sessions SET scheduled_start_at=now() WHERE id=1",
            "UPDATE aives.exam_sessions SET scheduled_end_at=now() WHERE id=1",
            "UPDATE aives.exam_sessions SET scheduled_start_at=now(),scheduled_end_at=now() WHERE id=1",
            "UPDATE aives.exam_sessions SET scheduled_start_at=now(),scheduled_end_at=now()-interval '1 second' WHERE id=1",
            "UPDATE aives.student_exams SET scheduled_start_at=now() WHERE id=1",
            "UPDATE aives.student_exams SET scheduled_end_at=now() WHERE id=1",
            "UPDATE aives.student_exams SET scheduled_start_at=now(),scheduled_end_at=now() WHERE id=1",
            "UPDATE aives.student_exams SET scheduled_start_at=now(),scheduled_end_at=now()-interval '1 second' WHERE id=1",
            "UPDATE aives.exam_sessions SET slot_duration_minutes=0 WHERE id=1",
            "UPDATE aives.exam_sessions SET slot_duration_minutes=-1 WHERE id=1",
            "UPDATE aives.student_exams SET slot_order=0 WHERE id=1",
            "UPDATE aives.student_exams SET slot_order=-1 WHERE id=1"
    })
    void rejectsInvalidScheduleAtDatabaseBoundary(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Các khóa ngoại hiện hữu vẫn chặn từng loại quan hệ tới bản ghi không tồn tại. */
    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE aives.exam_sessions SET lecturer_id=9223372036854775807 WHERE id=1",
            "UPDATE aives.exam_sessions SET subject_id=9223372036854775807 WHERE id=1",
            "UPDATE aives.student_exams SET session_id=9223372036854775807 WHERE id=1",
            "UPDATE aives.student_exams SET student_id=9223372036854775807 WHERE id=1",
            "UPDATE aives.student_exam_questions SET student_exam_id=9223372036854775807 WHERE id=1",
            "UPDATE aives.student_exam_questions SET question_id=9223372036854775807 WHERE id=1"
    })
    void rejectsInvalidForeignKeys(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** UNIQUE gốc và mới bảo vệ dữ liệu ngay cả khi ghi trực tiếp không qua service. */
    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO aives.student_exams(session_id,student_id) VALUES (1,2)",
            "INSERT INTO aives.student_exams(session_id,student_id,slot_order) VALUES (1,1,1)",
            "INSERT INTO aives.student_exam_questions(student_exam_id,question_id,question_order) VALUES (1,1,2)",
            "INSERT INTO aives.student_exam_questions(student_exam_id,question_id,question_order) VALUES (1,(SELECT max(id) FROM aives.questions),1)"
    })
    void rejectsDuplicateRelationshipsAndOrders(String sql) {
        jdbc.update("UPDATE aives.student_exams SET slot_order=1 WHERE id=1");
        newQuestion("Câu thứ hai");
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Hai sinh viên chưa có thứ tự trong cùng phiên và thứ tự giống nhau ở hai phiên đều hợp lệ. */
    @Test
    void supportsNullLegacyOrdersAndSameOrderAcrossSessions() {
        ExamSession a = sessions.findById(1L).orElseThrow();
        StudentExam first = exams.saveAndFlush(studentExam(a, student("null-slot"), null));
        assertThat(first.getStatus()).isEqualTo(StudentExamStatus.ASSIGNED);
        assertThat(exams.findById(1L).orElseThrow().getSlotOrder()).isNull();
        StudentExam one = exams.saveAndFlush(studentExam(a, student("slot-one"), 1));
        StudentExam two = exams.saveAndFlush(studentExam(sessions.saveAndFlush(session("Phiên khác")), student("other-slot"), 1));
        assertThat(one.getSlotOrder()).isEqualTo(two.getSlotOrder());
    }

    /** Kiểm tra giới hạn trang và thứ tự ổn định: slot có giá trị trước, NULL cuối và ID phá hòa. */
    @Test
    void repositoryQueriesFilterPaginateAndOrderDeterministically() {
        ExamSession a = sessions.saveAndFlush(session("Phiên A"));
        sessions.saveAndFlush(session("Phiên B"));
        StudentExam second = exams.saveAndFlush(studentExam(a, student("second"), 2));
        StudentExam legacy = exams.saveAndFlush(studentExam(a, student("legacy"), null));
        StudentExam legacy2 = exams.saveAndFlush(studentExam(a, student("legacy2"), null));
        StudentExam first = exams.saveAndFlush(studentExam(a, student("first"), 1));
        first.setStatus(StudentExamStatus.IN_PROGRESS);
        exams.flush();
        var page = PageRequest.of(0, 2);
        assertThat(sessions.findByLecturerIdOrderByIdAsc(1L, page).getTotalElements()).isEqualTo(3);
        assertThat(sessions.findBySubjectIdOrderByIdAsc(1L, page).getContent()).hasSize(2);
        assertThat(sessions.findByLecturerIdOrderByIdAsc(Long.MAX_VALUE, page)).isEmpty();
        assertThat(sessions.existsByIdAndLecturerId(a.getId(), 1L)).isTrue();
        assertThat(sessions.existsByIdAndLecturerId(a.getId(), 2L)).isFalse();
        assertThat(exams.findScheduledBySessionId(a.getId(), page).map(StudentExam::getId).getContent())
                .containsExactly(first.getId(), second.getId());
        assertThat(exams.findScheduledBySessionId(a.getId(), PageRequest.of(1, 2)).map(StudentExam::getId).getContent())
                .containsExactly(legacy.getId(), legacy2.getId());
        assertThat(exams.existsBySessionIdAndStudentId(a.getId(), first.getStudent().getId())).isTrue();
        assertThat(exams.existsBySessionIdAndStudentId(1L, first.getStudent().getId())).isFalse();
        assertThat(exams.existsByStudentIdAndStatus(first.getStudent().getId(), StudentExamStatus.IN_PROGRESS)).isTrue();
        assertThat(exams.existsByStudentIdAndStatus(first.getStudent().getId(), StudentExamStatus.COMPLETED)).isFalse();
        assertThat(exams.findByStudentIdOrderByIdAsc(first.getStudent().getId(), page).getTotalElements()).isEqualTo(1);
        Question q = newQuestion("Câu mới");
        links.saveAndFlush(StudentExamQuestion.builder().studentExam(first).question(q).questionOrder(2).build());
        links.saveAndFlush(StudentExamQuestion.builder().studentExam(first).question(em.getReference(Question.class, 1L)).questionOrder(1).build());
        links.saveAndFlush(StudentExamQuestion.builder().studentExam(second).question(q).questionOrder(1).build());
        assertThat(links.findByStudentExamIdOrderByQuestionOrderAscIdAsc(first.getId(), page)
                .map(StudentExamQuestion::getQuestionOrder).getContent()).containsExactly(1, 2);
        assertThat(links.existsByStudentExamIdAndQuestionId(first.getId(), q.getId())).isTrue();
        assertThat(links.existsByStudentExamIdAndQuestionId(legacy.getId(), q.getId())).isFalse();
        assertThat(links.findUsedQuestionIdsBySessionId(a.getId(), page).getContent()).containsExactly(1L, q.getId());
        assertThat(links.findUsedQuestionIdsBySessionId(a.getId(), page).getTotalElements()).isEqualTo(2);
        assertThat(links.findUsedQuestionIdsBySessionId(Long.MAX_VALUE, page)).isEmpty();
        assertThat(questions.countExamReferences(q.getId())).isEqualTo(2);
        assertThat(questions.countExamReferences(Long.MAX_VALUE)).isZero();
    }

    /** Dữ liệu trước migration vẫn đọc được mà không có lịch giả, giữ điểm và thời gian thực tế. */
    @Test
    void readsLegacyRecordsWithoutInventingSchedules() {
        ExamSession session = sessions.findById(1L).orElseThrow();
        StudentExam exam = exams.findById(1L).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(ExamSessionStatus.COMPLETED);
        assertThat(session.getScheduledStartAt()).isNull();
        assertThat(session.getScheduledEndAt()).isNull();
        assertThat(session.getSlotDurationMinutes()).isNull();
        assertThat(exam.getScheduledStartAt()).isNull();
        assertThat(exam.getScheduledEndAt()).isNull();
        assertThat(exam.getSlotOrder()).isNull();
        assertThat(exam.getFinalScore()).isEqualByComparingTo("8.50");
        assertThat(exam.getCompletedAt().toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-01T02:15:00Z").toInstant());
        assertThat(questions.countExamReferences(1L)).isEqualTo(1);
    }

    /** Xóa phiên chỉ cascade xuống bài thi/liên kết; câu ngân hàng, môn và user vẫn còn. */
    @Test
    void deletingSessionNeverCascadesToQuestionSubjectOrUser() {
        sessions.deleteById(1L);
        sessions.flush();
        em.clear();
        assertThat(exams.findById(1L)).isEmpty();
        assertThat(links.findById(1L)).isEmpty();
        assertThat(questions.findById(1L)).isPresent();
        assertThat(em.find(Subject.class, 1L)).isNotNull();
        assertThat(em.find(User.class, 2L)).isNotNull();
    }

    /** Các CHECK cũ về trạng thái, điểm, thời gian và số câu vẫn có hiệu lực. */
    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE aives.exam_sessions SET status='CANCELLED' WHERE id=1",
            "UPDATE aives.student_exams SET status='ABSENT' WHERE id=1",
            "UPDATE aives.exam_sessions SET max_main_questions=0 WHERE id=1",
            "UPDATE aives.exam_sessions SET max_follow_ups=-1 WHERE id=1",
            "UPDATE aives.student_exams SET final_score=10.01 WHERE id=1",
            "UPDATE aives.student_exams SET ai_suggested_score=-0.01 WHERE id=1",
            "UPDATE aives.student_exams SET completed_at=started_at-interval '1 second' WHERE id=1",
            "UPDATE aives.student_exam_questions SET question_order=0 WHERE id=1",
            "DELETE FROM aives.questions WHERE id=1",
            "DELETE FROM aives.subjects WHERE id=1",
            "DELETE FROM aives.users WHERE id=2"
    })
    void preservesOriginalConstraintBehavior(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Tạo phiên mới tham chiếu giảng viên/môn trong fixture, không thực hiện nghiệp vụ API. */
    private ExamSession session(String title) {
        return ExamSession.builder().lecturer(em.getReference(User.class, 1L))
                .subject(em.getReference(Subject.class, 1L)).title(title).build();
    }

    /** Tạo sinh viên riêng để không vi phạm UNIQUE phiên/sinh viên khi test lịch. */
    private User student(String username) {
        User user = User.builder().username(username).email(username + "@test.invalid").fullName(username)
                .role(em.getReference(Role.class, 2L)).build();
        em.persist(user);
        return user;
    }

    /** Tạo bài thi dùng trạng thái mặc định ASSIGNED và lịch chưa xác định. */
    private StudentExam studentExam(ExamSession session, User student, Integer slot) {
        return StudentExam.builder().session(session).student(student).slotOrder(slot).build();
    }

    /** Lưu Question bằng mapping hiện có để kiểm chứng tương thích Feature 1. */
    private Question newQuestion(String content) {
        Question question = Question.builder().subject(em.getReference(Subject.class, 1L))
                .createdBy(em.getReference(User.class, 1L)).content(content).createdAt(scheduled).updatedAt(scheduled)
                .sourceType(QuestionSourceType.MANUAL).status(QuestionStatus.DRAFT)
                .isAiGenerated(false).isApproved(false).build();
        return questions.saveAndFlush(question);
    }

    /** Môn mới và câu hỏi mới của Feature 1 vẫn ghi/đọc được sau khi thêm mapping thi. */
    @Test
    void existingSubjectAndQuestionPersistNormally() {
        Subject subject = Subject.builder().code("NEW101").name("Môn mới").description("Giữ mapping cũ").build();
        em.persist(subject);
        Question question = newQuestion("Câu hỏi tương thích");
        question.setSubject(subject);
        questions.flush();
        em.clear();
        assertThat(questions.findById(question.getId()).orElseThrow().getSubject().getCode()).isEqualTo("NEW101");
    }
}
