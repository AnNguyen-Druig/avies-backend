package com.avies.backend.exam;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.exception.*;
import com.avies.backend.service.ExamQuestionSelectionService;
import com.avies.backend.service.ExamSnapshotService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import com.avies.backend.entity.enums.BloomLevel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import com.avies.backend.dto.response.SelectedMainQuestion;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import static org.assertj.core.api.Assertions.*;

/** Kiểm tra lịch sử và migration thật trên PostgreSQL Docker riêng; không đọc cấu hình Supabase. */
@SpringJUnitConfig(ExamQuestionSelectionPostgresConfig.class)
@WebAppConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExamSnapshotPostgresIT {
    /** JWT giả, không nạp signer hoặc gọi dịch vụ bên ngoài. */
    @MockitoBean private CustomJwtDecoder decoder;
    /** Database duy nhất của ca kiểm thử. */
    @Autowired private DataSource source;
    /** Transaction mô phỏng caller Start tin cậy. */
    @Autowired private PlatformTransactionManager manager;
    /** Service chọn và lưu snapshot thật. */
    @Autowired private ExamQuestionSelectionService selection;
    /** Reader chỉ dùng bằng chứng lịch sử. */
    @Autowired private ExamSnapshotService snapshots;
    /** Dùng controller/security thật để kiểm tra API hiện tại không lộ snapshot và khóa cấu hình. */
    @Autowired private WebApplicationContext context;
    /** SQL dùng kiểm tra kết quả commit từ connection độc lập. */
    private JdbcTemplate jdbc;
    /** Dataset mới cho mỗi ca. */
    private ExamQuestionSelectionFixture fixture;

    /** Chỉ reset dữ liệu container; không thao tác database của dự án đang chạy. */
    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(source);
        fixture = new ExamQuestionSelectionFixture(jdbc);
        fixture.reset();
    }

    /** Đổi cả câu, đáp án và rubric sau commit không đổi dữ liệu dùng chấm bài đã chọn. */
    @Test
    void gradingReadsOriginalEvidenceAfterEverySourceFieldChanges() {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1), question = fixture.question();
        jdbc.update("UPDATE aives.questions SET answer_time_limit_seconds=120 WHERE id=?", question);
        select(exam);
        var original = snapshots.loadForGrading(exam);
        jdbc.update("UPDATE aives.questions SET content='New body',reference_answer='New answer',bloom_level='APPLY',answer_time_limit_seconds=300,version=version+1,archived_at=now() WHERE id=?", question);
        jdbc.update("UPDATE aives.rubrics SET title='New title',description='New description',updated_at=now() WHERE subject_id=1");
        jdbc.update("UPDATE aives.rubric_criteria SET criterion_text='New criterion',point_weight=2 WHERE rubric_id=(SELECT id FROM aives.rubrics WHERE subject_id=1)");
        assertThat(snapshots.loadForGrading(exam)).isEqualTo(original);
        assertThat(original.questions().getFirst().referenceAnswer()).isEqualTo("PRIVATE_REFERENCE_ANSWER");
        assertThat(original.questions().getFirst().answerTimeLimitSeconds()).isEqualTo(120);
        var ids = select(exam);
        assertThat(select(exam)).extracting(row -> row.getStudentExamQuestionId())
                .containsExactly(ids.getFirst().getStudentExamQuestionId());
        assertThat(snapshots.loadForGrading(exam)).isEqualTo(original);
    }

    /** Mọi sinh viên trong phiên giữ rubric đầu tiên dù nguồn bị xóa rồi tạo lại với nội dung khác. */
    @Test
    void subsequentStudentUsesSameRubricEvenAfterOriginalRubricIsDeleted() {
        long parent = fixture.session(1), first = fixture.exam(parent, 4, 1), next = fixture.exam(parent, 6, 2);
        fixture.question(); fixture.question();
        select(first);
        var original = snapshots.loadForGrading(first);
        jdbc.update("DELETE FROM aives.rubrics WHERE subject_id=1");
        jdbc.update("UPDATE aives.exam_sessions SET status='ONGOING' WHERE id=?", parent);
        select(next);
        assertThat(snapshots.loadForGrading(next).rubric()).isEqualTo(original.rubric());
        assertThat(snapshots.loadForGrading(next).rubricCapturedAt()).isEqualTo(original.rubricCapturedAt());
        assertThat(snapshots.loadForGrading(first)).isEqualTo(original);
    }

    /** Không biến đáp án hoặc thời lượng chưa cấu hình thành giá trị giả trong lịch sử. */
    @Test
    void optionalAnswerAndTimeLimitRemainNull() {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1), question = fixture.question();
        jdbc.update("UPDATE aives.questions SET reference_answer=null,answer_time_limit_seconds=null WHERE id=?", question);
        select(exam);
        var saved = snapshots.loadForGrading(exam).questions().getFirst();
        assertThat(saved.referenceAnswer()).isNull();
        assertThat(saved.answerTimeLimitSeconds()).isNull();
        assertThat(saved.sourceVersion()).isZero();
    }

    /** Schema đã nâng cấp và snapshot phải hỗ trợ đủ sáu Bloom, không chỉ Java enum. */
    @ParameterizedTest
    @EnumSource(BloomLevel.class)
    void snapshotSupportsAllMigratedBloomLevels(BloomLevel bloom) {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1);
        fixture.question(1, "APPROVED", bloom.name());
        select(exam);
        assertThat(snapshots.loadForGrading(exam).questions().getFirst().bloomLevel()).isEqualTo(bloom.name());
    }

    /** Có rubric phiên hợp lệ không làm hợp thức hóa các liên kết câu legacy thiếu nội dung chụp. */
    @Test
    void missingQuestionSnapshotIsNotFilledFromBankEvenWhenRubricExists() {
        long parent = fixture.session(1), first = fixture.exam(parent, 4, 1), next = fixture.exam(parent, 6, 2);
        long question = fixture.question(); select(first);
        jdbc.update("INSERT INTO aives.student_exam_questions(student_exam_id,question_id,question_order) VALUES (?,?,1)", next, question);
        assertCode(() -> select(next), ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
        assertCode(() -> snapshots.loadForGrading(next), ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
    }

    /** Verifier SQL thật báo PASS cho cột, constraint và trigger của schema container mới. */
    @Test
    void postcheckScriptConfirmsInstalledSnapshotSchema() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            assertThat(statement.execute(ExamFoundationPostgresConfig.projectSql("docs/migrations/feature2_snapshot_postcheck.sql"))).isTrue();
            for (int expected : new int[]{8, 2, 2}) {
                int rows = 0;
                try (var result = statement.getResultSet()) {
                    while (result.next()) { assertThat(result.getString(3)).isEqualTo("PASS"); rows++; }
                }
                assertThat(rows).isEqualTo(expected);
                statement.getMoreResults();
            }
        }
    }

    /** Khóa bất biến ở DB không phụ thuộc caller có sử dụng entity JPA hay không. */
    @ParameterizedTest
    @ValueSource(strings = {"snapshot_content='Other'", "snapshot_reference_answer='Other'",
            "snapshot_bloom_level='APPLY'", "snapshot_question_version=2", "snapshot_answer_time_limit_seconds=400",
            "snapshot_captured_at=null", "question_order=2"})
    void directUpdatesCannotRewriteQuestionEvidence(String assignment) {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1);
        fixture.question(); select(exam);
        var original = snapshots.loadForGrading(exam);
        assertThatThrownBy(() -> jdbc.update("UPDATE aives.student_exam_questions SET " + assignment + " WHERE student_exam_id=?", exam))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(snapshots.loadForGrading(exam)).isEqualTo(original);
    }

    /** Không đổi rubric hoặc cấu hình đề đã dùng cho các lượt thi trong cùng phiên. */
    @ParameterizedTest
    @ValueSource(strings = {"rubric_snapshot=null", "rubric_snapshot_at=null", "max_main_questions=2", "max_follow_ups=4", "subject_id=2"})
    void directUpdatesCannotRewriteSessionGradingContract(String assignment) {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1);
        fixture.question(); select(exam);
        assertThatThrownBy(() -> jdbc.update("UPDATE aives.exam_sessions SET " + assignment + " WHERE id=?", parent))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(snapshots.loadForGrading(exam).questions()).hasSize(1);
    }

    /** Bộ legacy dù đủ ID/thứ tự vẫn không được coi là đã có bằng chứng lịch sử. */
    @Test
    void legacySetIsRejectedWithoutBackfillingFromLiveBank() {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1), question = fixture.question();
        jdbc.update("INSERT INTO aives.student_exam_questions(student_exam_id,question_id,question_order) VALUES (?,?,1)", exam, question);
        assertCode(() -> select(exam), ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
        assertCode(() -> snapshots.loadForGrading(exam), ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT snapshot_captured_at IS NULL FROM aives.student_exam_questions WHERE student_exam_id=?", Boolean.class, exam)).isTrue();
        long next = fixture.exam(parent, 6, 2);
        assertCode(() -> select(next), ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT rubric_snapshot IS NULL FROM aives.exam_sessions WHERE id=?", Boolean.class, parent)).isTrue();
    }

    /** Bài có snapshot câu nhưng không có rubric cũng không được khôi phục rubric từ môn hiện tại. */
    @Test
    void questionSnapshotWithoutSessionSnapshotCannotBeReconstructed() {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1), question = fixture.question();
        jdbc.update("""
                INSERT INTO aives.student_exam_questions(student_exam_id,question_id,question_order,snapshot_content,
                    snapshot_bloom_level,snapshot_question_version,snapshot_captured_at)
                VALUES (?,?,1,'Old question','REMEMBER',0,now())
                """, exam, question);
        assertCode(() -> select(exam), ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
    }

    /** Snapshot rubric không bị commit riêng khi caller Start thất bại sau lúc chọn xong câu. */
    @Test
    void callerFailureRollsBackRubricAndQuestionSnapshotsTogether() {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1);
        fixture.question();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            selection.selectForStart(exam);
            throw new IllegalStateException("Start failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT rubric_snapshot IS NULL AND rubric_snapshot_at IS NULL FROM aives.exam_sessions WHERE id=?", Boolean.class, parent)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aives.student_exam_questions", Integer.class)).isZero();
    }

    /** Chạy lại migration thật giữ nguyên mọi bản chụp và không điền dữ liệu legacy. */
    @Test
    void migrationCanBeRerunWithoutChangingExistingOrLegacyRows() throws Exception {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1);
        long question = fixture.question(); select(exam);
        var original = snapshots.loadForGrading(exam);
        long legacyParent = fixture.session(1), legacyExam = fixture.exam(legacyParent, 6, 1);
        jdbc.update("INSERT INTO aives.student_exam_questions(student_exam_id,question_id,question_order) VALUES (?,?,1)", legacyExam, question);
        try (var connection = source.getConnection()) {
            ExamFoundationPostgresConfig.execute(connection, ExamFoundationPostgresConfig.projectSql("docs/migrations/20261025_feature2_exam_snapshots.sql"));
        }
        assertThat(snapshots.loadForGrading(exam)).isEqualTo(original);
        assertCode(() -> snapshots.loadForGrading(legacyExam), ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
    }

    /** Reader không chấp nhận payload sai cấu trúc dù JSON cú pháp hợp lệ. */
    @Test
    void malformedLegacyRubricPayloadHasSafeBusinessError() {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1);
        jdbc.update("UPDATE aives.exam_sessions SET rubric_snapshot='{\"schemaVersion\":1,\"criteria\":[]}',rubric_snapshot_at=now() WHERE id=?", parent);
        assertCode(() -> snapshots.loadForGrading(exam), ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
        assertCode(() -> select(exam), ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
    }

    /** Chặn đổi số câu/follow-up sau khi chốt; PATCH cùng giá trị và sửa tiêu đề vẫn hợp lệ. */
    @Test
    void existingCrudKeepsFrozenConfigurationAndDoesNotExposeAnswers() throws Exception {
        long parent = fixture.session(1), exam = fixture.exam(parent, 4, 1);
        fixture.question(); select(exam);
        var original = snapshots.loadForGrading(exam);
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity()).build();
        for (String body : new String[]{"{\"maxMainQuestions\":2}", "{\"maxFollowUps\":4}"}) {
            mvc.perform(ExamSessionFixture.as(patch("/exam-sessions/" + parent)
                    .contentType(MediaType.APPLICATION_JSON).content(body), "owner"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(1302));
        }
        mvc.perform(ExamSessionFixture.as(patch("/exam-sessions/" + parent)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Tên mới\",\"maxMainQuestions\":1}"), "owner"))
                .andExpect(status().isOk());
        String lecturerBody = mvc.perform(ExamSessionFixture.as(get("/exam-sessions/" + parent), "owner"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String studentBody = mvc.perform(ExamSessionFixture.as(get("/student-exams/me"), "student"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(lecturerBody + studentBody).doesNotContain("PRIVATE_REFERENCE_ANSWER", "PRIVATE_RUBRIC", "rubricSnapshot", "snapshotReferenceAnswer");
        assertThat(snapshots.loadForGrading(exam)).isEqualTo(original);
    }

    /** Mỗi lần chọn có transaction ghi riêng; không khởi động workflow Start thật. */
    private List<SelectedMainQuestion> select(long exam) {
        return new TransactionTemplate(manager).execute(tx -> selection.selectForStart(exam));
    }

    /** Kiểm tra mã lỗi ổn định và không dựa vào thông tin SQL nội bộ. */
    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AppException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
