package com.avies.backend.exam;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.dto.response.SelectedMainQuestion;
import com.avies.backend.exception.*;
import com.avies.backend.repository.*;
import com.avies.backend.service.ExamQuestionSelectionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Repository/service/JPA thật trên PostgreSQL tạm, không gọi AI, backend live hoặc Supabase. */
@SpringJUnitConfig(ExamQuestionSelectionPostgresConfig.class)
@WebAppConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExamQuestionSelectionPostgresIT {
    /** Không đọc signer key hoặc dịch vụ xác thực thật. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Datasource chỉ thuộc container Testcontainers trong config này. */
    @Autowired private DataSource source;
    /** Mở transaction ghi thay cho Start workflow chưa triển khai. */
    @Autowired private PlatformTransactionManager manager;
    /** Service thật được proxy transaction/validation. */
    @Autowired private ExamQuestionSelectionService service;
    /** Kiểm tra các query scalar/native thật. */
    @Autowired private QuestionRepository questions;
    /** Kiểm tra query lịch sử đúng phạm vi phiên. */
    @Autowired private StudentExamQuestionRepository links;
    /** Đối chiếu dữ liệu commit và chuẩn bị fixture. */
    private JdbcTemplate jdbc;
    /** Không nhận URL, token hoặc ID của môi trường chung. */
    private ExamQuestionSelectionFixture fixture;

    /** Mỗi ca reset dataset trong container, không sửa cấu trúc schema. */
    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(source);
        fixture = new ExamQuestionSelectionFixture(jdbc);
        fixture.reset();
    }

    /** Subject/status/archived/Bloom/rubric lọc đúng; chỉ lưu đủ N và giữ nguyên trạng thái thi. */
    @Test
    void selectsExactlyEligibleQuestionsWithStableOrderAndNoStateChanges() throws Exception {
        long parent = fixture.session(2);
        long exam = fixture.exam(parent, 4, 1);
        long a = fixture.question(1, "APPROVED", "REMEMBER");
        long b = fixture.question(1, "APPROVED", "ANALYZE");
        fixture.question(2, "APPROVED", "APPLY");
        fixture.question(1, "DRAFT", "UNDERSTAND");
        fixture.question(1, "PENDING_REVIEW", "UNDERSTAND");
        fixture.question(1, "REJECTED", "UNDERSTAND");
        long archived = fixture.question();
        jdbc.update("UPDATE aives.questions SET archived_at=now() WHERE id=?", archived);
        long nullBloom = fixture.question();
        jdbc.update("UPDATE aives.questions SET bloom_level=null WHERE id=?", nullBloom);
        var result = select(exam);
        assertThat(result).hasSize(2).extracting(SelectedMainQuestion::getQuestionId).containsExactlyInAnyOrder(a, b);
        assertThat(result).extracting(SelectedMainQuestion::getQuestionOrder).containsExactly(1, 2);
        assertThat(result).extracting(SelectedMainQuestion::getStudentExamQuestionId).doesNotContainNull();
        assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, exam)).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT started_at FROM aives.student_exams WHERE id=?", java.time.OffsetDateTime.class, exam)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM aives.exam_sessions WHERE id=?", String.class, parent)).isEqualTo("UPCOMING");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        assertThat(json).doesNotContain("PRIVATE_REFERENCE_ANSWER", "PRIVATE_RUBRIC", "referenceAnswer", "pointWeight", "content");
    }

    /** Criteria join được gom thành một câu, không làm N trùng hoặc đếm nhầm số câu. */
    @Test
    void nativeScalarProjectionCarriesAllCriteriaWithoutEntityCache() {
        long question = fixture.question();
        var rows = questions.findSelectionPool(1L);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.getQuestionId()).containsOnly(question);
        assertThat(rows).extracting(r -> r.getPointWeight()).containsExactly(new java.math.BigDecimal("4.00"), new java.math.BigDecimal("6.00"));
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getArchived()).isFalse();
            assertThat(row.getQuestionVersion()).isEqualTo(0);
            assertThat(row.getRubricId()).isNotNull();
        });
    }

    /** Bộ đủ đã lưu không random lại, không thay ID/thứ tự dù sau đó câu bị archive hoặc bài hoàn tất. */
    @Test
    void retriesReturnOriginalSetAndPreserveEvidenceAfterCompletion() {
        long parent = fixture.session(2);
        long exam = fixture.exam(parent, 4, 1);
        for (int i = 0; i < 4; i++) fixture.question();
        var first = select(exam);
        var before = jdbc.queryForList("SELECT * FROM aives.student_exam_questions WHERE student_exam_id=? ORDER BY question_order", exam);
        jdbc.update("UPDATE aives.student_exams SET status='COMPLETED',started_at=now(),completed_at=now(),final_score=8 WHERE id=?", exam);
        jdbc.update("UPDATE aives.exam_sessions SET status='COMPLETED' WHERE id=?", parent);
        jdbc.update("UPDATE aives.questions SET archived_at=now() WHERE id=?", first.getFirst().getQuestionId());
        var second = select(exam);
        assertThat(second).extracting(SelectedMainQuestion::getStudentExamQuestionId)
                .containsExactlyElementsOf(first.stream().map(SelectedMainQuestion::getStudentExamQuestionId).toList());
        assertThat(jdbc.queryForList("SELECT * FROM aives.student_exam_questions WHERE student_exam_id=? ORDER BY question_order", exam)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT final_score FROM aives.student_exams WHERE id=?", java.math.BigDecimal.class, exam)).isEqualByComparingTo("8");
    }

    /** Bộ thiếu/thừa/lệch thứ tự không bị lấp hoặc xóa, giữ cả dữ liệu đã dùng. */
    @ParameterizedTest
    @ValueSource(strings = {"partial", "extra", "gap", "config_changed"})
    void rejectsInconsistentPersistedSetsWithoutChangingThem(String issue) {
        long parent = fixture.session(2);
        long exam = fixture.exam(parent, 4, 1);
        fixture.link(exam, fixture.question(), 1);
        if (!issue.equals("partial")) fixture.link(exam, fixture.question(), issue.equals("gap") ? 3 : 2);
        if (issue.equals("extra")) fixture.link(exam, fixture.question(), 3);
        if (issue.equals("config_changed")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE aives.exam_sessions SET max_main_questions=3 WHERE id=?", parent))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(select(exam)).hasSize(2);
            return;
        }
        var before = jdbc.queryForList("SELECT * FROM aives.student_exam_questions ORDER BY id");
        assertCode(() -> select(exam), ErrorCode.EXAM_QUESTION_SET_INCONSISTENT);
        assertThat(jdbc.queryForList("SELECT * FROM aives.student_exam_questions ORDER BY id")).isEqualTo(before);
    }

    /** Thiếu distinct câu cơ bản phải lỗi rõ, không sinh AI hoặc tạo bộ ít hơn cấu hình. */
    @Test
    void insufficientDistinctPoolFailsWithNoLinks() {
        long parent = fixture.session(3);
        long exam = fixture.exam(parent, 4, 1);
        fixture.question(); fixture.question();
        assertCode(() -> select(exam), ErrorCode.EXAM_QUESTION_POOL_INSUFFICIENT);
        assertCount(0);
    }

    /** Rubric thiếu/rỗng/sai tổng/điểm bằng 0/blank đều bị loại và trả lỗi rubric nếu không còn đủ bộ. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "sum", "zero", "blank"})
    void invalidRubricsCannotProduceAQuestionSet(String issue) {
        long parent = fixture.session(1);
        long exam = fixture.exam(parent, 4, 1);
        long question = fixture.question();
        switch (issue) {
            case "missing" -> jdbc.update("DELETE FROM aives.rubrics WHERE subject_id=(SELECT subject_id FROM aives.questions WHERE id=?)", question);
            case "empty" -> jdbc.update("DELETE FROM aives.rubric_criteria WHERE rubric_id=(SELECT id FROM aives.rubrics WHERE subject_id=(SELECT subject_id FROM aives.questions WHERE id=?))", question);
            case "sum" -> jdbc.update("UPDATE aives.rubric_criteria SET point_weight=2 WHERE rubric_id=(SELECT id FROM aives.rubrics WHERE subject_id=(SELECT subject_id FROM aives.questions WHERE id=?))", question);
            case "zero" -> jdbc.update("UPDATE aives.rubric_criteria SET point_weight=0 WHERE rubric_id=(SELECT id FROM aives.rubrics WHERE subject_id=(SELECT subject_id FROM aives.questions WHERE id=?)) AND display_order=1", question);
            case "blank" -> jdbc.update("UPDATE aives.rubric_criteria SET criterion_text=' ' WHERE rubric_id=(SELECT id FROM aives.rubrics WHERE subject_id=(SELECT subject_id FROM aives.questions WHERE id=?))", question);
        }
        assertCode(() -> select(exam), ErrorCode.EXAM_QUESTION_RUBRIC_INVALID);
        assertCount(0);
    }

    /** Tránh hoàn toàn câu của sinh viên trước nếu còn đủ pool, lịch sử phiên khác không bị lẫn. */
    @Test
    void historyIsSessionScopedAndPreviousStudentsQuestionsAreAvoided() {
        long parent = fixture.session(2);
        long previous = fixture.exam(parent, 4, 1);
        long target = fixture.exam(parent, 6, 2);
        long otherParent = fixture.session(2);
        long otherExam = fixture.exam(otherParent, 7, 1);
        long a = fixture.question(), b = fixture.question(), c = fixture.question(), d = fixture.question();
        fixture.link(previous, a, 1); fixture.link(previous, b, 2);
        fixture.link(otherExam, c, 1); fixture.link(otherExam, d, 2);
        jdbc.update("UPDATE aives.student_exams SET started_at=now(),status='IN_PROGRESS' WHERE id=?", previous);
        var history = new TransactionTemplate(manager).execute(tx -> links.findSelectionHistory(parent, target));
        assertThat(history).hasSize(2).extracting(row -> row.getStudentExam().getId()).containsOnly(previous);
        assertThat(select(target)).extracting(SelectedMainQuestion::getQuestionId).containsExactlyInAnyOrder(c, d);
    }

    /** Không đủ ngân hàng tránh lặp toàn phiên vẫn chọn đủ N distinct với overlap nhỏ nhất. */
    @Test
    void fallbackRepeatsAcrossStudentsButNeverWithinOneStudent() {
        long parent = fixture.session(2);
        long previous = fixture.exam(parent, 4, 1), target = fixture.exam(parent, 6, 2);
        long a = fixture.question(), b = fixture.question(), c = fixture.question();
        fixture.link(previous, a, 1); fixture.link(previous, b, 2);
        var result = select(target);
        assertThat(result).hasSize(2);
        assertThat(result.stream().map(SelectedMainQuestion::getQuestionId).distinct()).hasSize(2);
        assertThat(result).extracting(SelectedMainQuestion::getQuestionId).contains(c);
    }

    /** Cân các Bloom đang được schema fixture chấp nhận khi mọi câu đều chưa dùng. */
    @Test
    void balancesBloomWithoutHardcodingAnExactDistribution() {
        long parent = fixture.session(3);
        long exam = fixture.exam(parent, 4, 1);
        fixture.question(1, "APPROVED", "REMEMBER");
        fixture.question(1, "APPROVED", "REMEMBER");
        fixture.question(1, "APPROVED", "APPLY");
        fixture.question(1, "APPROVED", "ANALYZE");
        select(exam);
        assertThat(jdbc.queryForList("SELECT q.bloom_level FROM aives.student_exam_questions a JOIN aives.questions q ON q.id=a.question_id WHERE a.student_exam_id=?", String.class, exam))
                .containsExactlyInAnyOrder("REMEMBER", "APPLY", "ANALYZE");
    }

    /** Cho phép chuẩn bị bài ASSIGNED khi phiên đang ONGOING, không đổi status của cả hai. */
    @Test
    void supportsSubsequentStudentWithinOngoingSession() {
        long parent = fixture.session(1);
        long exam = fixture.exam(parent, 4, 1);
        fixture.question();
        jdbc.update("UPDATE aives.exam_sessions SET status='ONGOING' WHERE id=?", parent);
        assertThat(select(exam)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, exam)).isEqualTo("ASSIGNED");
    }

    /** Không chế tạo bộ mới sau startedAt/evidence hoặc khi parent hoàn tất. */
    @ParameterizedTest
    @ValueSource(strings = {"parent", "status", "started_at", "completed_at", "score", "audio"})
    void refusesNewSetAfterExamUse(String state) {
        long parent = fixture.session(1);
        long exam = fixture.exam(parent, 4, 1);
        fixture.question();
        switch (state) {
            case "parent" -> jdbc.update("UPDATE aives.exam_sessions SET status='COMPLETED' WHERE id=?", parent);
            case "status" -> jdbc.update("UPDATE aives.student_exams SET status='IN_PROGRESS' WHERE id=?", exam);
            case "started_at", "completed_at" -> jdbc.update("UPDATE aives.student_exams SET " + state + "=now() WHERE id=?", exam);
            case "score" -> jdbc.update("UPDATE aives.student_exams SET ai_suggested_score=0 WHERE id=?", exam);
            case "audio" -> jdbc.update("UPDATE aives.student_exams SET audio_record_url='' WHERE id=?", exam);
        }
        assertCode(() -> select(exam), ErrorCode.EXAM_QUESTION_SELECTION_NOT_ALLOWED);
        assertCount(0);
    }

    /** Caller không có transaction ghi bị từ chối, tránh selection commit riêng trước Start thất bại. */
    @Test
    void requiresCallerOwnedWritableTransaction() {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1);
        assertThatThrownBy(() -> service.selectForStart(exam)).isInstanceOf(IllegalTransactionStateException.class);
        var readOnly = new TransactionTemplate(manager); readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(tx -> service.selectForStart(exam))).isInstanceOf(IllegalTransactionStateException.class);
        assertCount(0);
    }

    /** Start tương lai lỗi sau selection phải rollback cả bộ câu và các trạng thái/timestamp cùng transaction. */
    @Test
    void callerFailureRollsBackSelectionAndStartStateTogether() {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1);
        fixture.question();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            service.selectForStart(exam);
            jdbc.update("UPDATE aives.student_exams SET status='IN_PROGRESS',started_at=now() WHERE id=?", exam);
            jdbc.update("UPDATE aives.exam_sessions SET status='ONGOING' WHERE id=?", parent);
            throw new IllegalStateException("Start fixture failed");
        })).isInstanceOf(IllegalStateException.class);
        assertCount(0);
        assertThat(jdbc.queryForObject("SELECT status FROM aives.student_exams WHERE id=?", String.class, exam)).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT status FROM aives.exam_sessions WHERE id=?", String.class, parent)).isEqualTo("UPCOMING");
    }

    /** FK vẫn bảo vệ câu nguồn khỏi xóa; snapshot không làm mất quy tắc tham chiếu hiện hành. */
    @Test
    void selectedQuestionCannotBeDeletedByDatabaseCascade() {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1), question = fixture.question();
        select(exam);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM aives.questions WHERE id=?", question)).isInstanceOf(DataIntegrityViolationException.class);
        assertCount(1);
    }

    /** ID bài không tồn tại không tạo thêm bản ghi hoặc lộ dữ liệu grading. */
    @Test
    void missingStudentExamUsesExistingSafeError() {
        assertCode(() -> select(99999), ErrorCode.STUDENT_EXAM_NOT_FOUND);
        assertCount(0);
    }

    /** Giao dịch ghi riêng cho mỗi lần gọi mô phỏng ranh giới Start, không bypass proxy service. */
    private List<SelectedMainQuestion> select(long exam) {
        return new TransactionTemplate(manager).execute(tx -> service.selectForStart(exam));
    }

    /** Dữ liệu được commit/rollback được kiểm tra bằng connection mới sau workflow. */
    private void assertCount(int count) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isEqualTo(count);
    }

    /** So ErrorCode, không so thông tin exception SQL hoặc rubric thật. */
    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AppException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
