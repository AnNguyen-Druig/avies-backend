package com.avies.backend.exam;

import com.avies.backend.entity.OralExamProgress;
import com.avies.backend.entity.enums.*;
import com.avies.backend.exception.*;
import com.avies.backend.service.support.OralExamCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Máy trạng thái thật với đồng hồ xác định; không gọi DB, AI hoặc tạo câu trả lời ngoài fixture. */
class OralExamCoordinatorTest {
    /** Bộ điều phối không có dependency hạ tầng. */ private final OralExamCoordinator engine = new OralExamCoordinator();
    /** Mốc giả cố định trong test. */ private final OffsetDateTime start = OffsetDateTime.parse("2035-01-01T10:00:00Z");

    /** Tạo hai câu đã snapshot, không liên quan dữ liệu Supabase. */
    private OralExamProgress create(int count, int followUps) {
        var p = new OralExamProgress(); p.setStudentExamId(10L); p.setPhase(OralExamPhase.PREPARING); p.setRevision(0L);
        p.setFollowUpsUsed(0); p.setMaxFollowUps(followUps); p.setMainCount(count);
        for (int i = 1; i <= count; i++) {
            var q = new OralExamProgress.Question(); q.setStudentExamQuestionId((long)i); q.setStudentExamId(10L); q.setQuestionOrder(i);
            q.setStatus(OralQuestionStatus.PENDING); q.setAnswerBudgetMs(90000L); q.setAnswerUsedMs(0L); p.getQuestions().add(q);
        }
        engine.next(p, start, id -> "Frozen main " + id); return p;
    }
    /** Mô phỏng callback worker, không phải đường nhận transcript HTTP Student. */
    private void listen(OralExamProgress p, OffsetDateTime time) {
        engine.promptReady(p, p.getCurrentTurnId(), "vi", time);
        engine.command(p, OralExamAction.PROMPT_FINISHED, null, time, id -> "Frozen main " + id);
    }
    /** Chỉ fixture worker gửi transcript; không cho Student tự quyết định follow-up. */
    private UUID transcript(OralExamProgress p, OffsetDateTime time, String answer) {
        engine.command(p, OralExamAction.SUBMIT, null, time, id -> "Frozen main " + id);
        UUID id = engine.activeSubmission(p).getId(); engine.transcript(p, id, answer, time); return id;
    }

    /** Nghe lại không tạo turn hoặc tăng follow-up/ngân sách. */
    @Test void replayKeepsIdentityAndRemainingBudget() {
        var p = create(2, 2); listen(p, start); var turnId = p.getCurrentTurnId();
        engine.synchronize(p, start.plusSeconds(30), start.plusMinutes(10), id -> "Frozen main " + id);
        engine.command(p, OralExamAction.REPLAY, null, start.plusSeconds(30), id -> "Frozen main " + id);
        engine.synchronize(p, start.plusSeconds(40), start.plusMinutes(10), id -> "Frozen main " + id);
        assertThat(p.getCurrentTurnId()).isEqualTo(turnId); assertThat(p.getTurns()).hasSize(1);
        assertThat(engine.remaining(p.getQuestions().getFirst())).isEqualTo(60000L); assertThat(p.getFollowUpsUsed()).isZero();
    }
    /** Câu bỏ qua chỉ xuất hiện sau lượt đầu, giữ nguyên thời gian đã dùng. */
    @Test void deferredQuestionReturnsAfterFirstPassWithOriginalBudget() {
        var p = create(2, 2); listen(p, start);
        engine.synchronize(p, start.plusSeconds(10), start.plusMinutes(10), id -> "Frozen main " + id);
        var turn = p.getCurrentTurnId(); engine.command(p, OralExamAction.DEFER, null, start.plusSeconds(10), id -> "Frozen main " + id);
        assertThatThrownBy(() -> engine.command(p, OralExamAction.REVISIT, 1, start.plusSeconds(10), id -> "Frozen main " + id)).isInstanceOf(AppException.class);
        listen(p, start.plusSeconds(10)); UUID submission = transcript(p, start.plusSeconds(15), "Trả lời câu 2");
        engine.decision(p, submission, false, null, start.plusSeconds(15), id -> "Frozen main " + id);
        assertThat(p.getPhase()).isEqualTo(OralExamPhase.REVIEW);
        engine.command(p, OralExamAction.REVISIT, 1, start.plusSeconds(15), id -> "Frozen main " + id);
        assertThat(p.getCurrentTurnId()).isEqualTo(turn); assertThat(engine.remaining(p.getQuestions().getFirst())).isEqualTo(80000L);
    }
    /** Follow-up là giới hạn toàn bài, không cấp lại khi chuyển câu chính. */
    @Test void followUpLimitAppliesAcrossAllMainQuestions() {
        var p = create(2, 1); listen(p, start); UUID first = transcript(p, start, "Trả lời chính");
        engine.decision(p, first, true, "Làm rõ ví dụ?", start, id -> "Frozen main " + id);
        assertThat(engine.currentTurn(p).getLanguage()).isEqualTo("vi"); listen(p, start);
        UUID second = transcript(p, start, "Giải thích ví dụ"); engine.decision(p, second, true, "Hỏi tiếp nhưng đã hết quota?", start, id -> "Frozen main " + id);
        assertThat(p.getCurrentQuestionOrder()).isEqualTo(2); assertThat(p.getFollowUpsUsed()).isEqualTo(1);
        listen(p, start); UUID third = transcript(p, start, "Trả lời câu 2"); engine.decision(p, third, true, "Không được tạo nữa?", start, id -> "Frozen main " + id);
        assertThat(p.getPhase()).isEqualTo(OralExamPhase.COMPLETED); assertThat(p.getTurns()).hasSize(3);
    }
    /** Không bắt buộc hỏi follow-up ngay cả khi còn quota. */
    @Test void clearAnswerCompletesWithoutUsingFollowUpQuota() {
        var p = create(1, 2); listen(p, start); UUID id = transcript(p, start, "Câu trả lời đầy đủ");
        engine.decision(p, id, false, null, start, ignored -> "Frozen main");
        assertThat(p.getPhase()).isEqualTo(OralExamPhase.COMPLETED); assertThat(p.getFollowUpsUsed()).isZero();
    }
    /** AI/TTS không trừ ngân sách câu; đồng hồ slot vẫn do caller kiểm tra. */
    @Test void speakingAndProcessingDoNotConsumeAnswerBudget() {
        var p = create(1, 2);
        engine.synchronize(p, start.plusSeconds(20), start.plusMinutes(10), id -> "Frozen main");
        listen(p, start.plusSeconds(20));
        engine.synchronize(p, start.plusSeconds(35), start.plusMinutes(10), id -> "Frozen main");
        engine.command(p, OralExamAction.SUBMIT, null, start.plusSeconds(35), id -> "Frozen main");
        engine.synchronize(p, start.plusMinutes(1), start.plusMinutes(10), id -> "Frozen main");
        assertThat(engine.remaining(p.getQuestions().getFirst())).isEqualTo(75000L);
    }
    /** Âm thanh rỗng không tạo transcript, không tự chuyển câu hoặc khôi phục ngân sách. */
    @ParameterizedTest @ValueSource(strings = {"", "  ", "\n\t"}) void emptySttKeepsSameTurnAndNoFakeAnswer(String empty) {
        var p = create(1, 2); listen(p, start); var turn = p.getCurrentTurnId();
        UUID id = transcript(p, start, empty);
        assertThat(p.getPhase()).isEqualTo(OralExamPhase.ANSWERING); assertThat(p.getCurrentTurnId()).isEqualTo(turn);
        assertThat(engine.currentTurn(p).getAnswerText()).isNull(); assertThat(p.getSubmissions().getFirst().getStatus()).isEqualTo("EMPTY");
        engine.transcript(p, id, empty, start); assertThat(p.getTurns()).hasSize(1);
    }
    /** Callback lặp chỉ giữ một transcript và một follow-up. */
    @Test void repeatedSttAndDecisionAreIdempotent() {
        var p = create(2, 2); listen(p, start); UUID id = transcript(p, start, "Câu trả lời");
        engine.transcript(p, id, "Câu trả lời", start); engine.decision(p, id, true, "Vì sao?", start, q -> "Frozen main");
        engine.decision(p, id, true, "Vì sao?", start, q -> "Frozen main");
        assertThat(p.getTurns()).hasSize(2); assertThat(p.getFollowUpsUsed()).isEqualTo(1);
        assertThatThrownBy(() -> engine.transcript(p, id, "Đổi kết quả", start)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> engine.decision(p, id, true, "Đổi câu hỏi?", start, q -> "Frozen main")).isInstanceOf(AppException.class);
    }
    /** Giọng đọc chỉ hỗ trợ hai ngôn ngữ đã chốt; follow-up không được đổi ngôn ngữ. */
    @Test void promptLanguageIsValidatedAndFollowUpInheritsIt() {
        var p = create(1, 1); UUID main = p.getCurrentTurnId();
        assertThatThrownBy(() -> engine.promptReady(p, main, "fr", start)).isInstanceOf(AppException.class);
        engine.promptReady(p, main, "en", start); engine.command(p, OralExamAction.PROMPT_FINISHED, null, start, id -> "Frozen main");
        UUID id = transcript(p, start, "English answer"); engine.decision(p, id, true, "Could you explain?", start, q -> "Frozen main");
        assertThatThrownBy(() -> engine.promptReady(p, p.getCurrentTurnId(), "vi", start)).isInstanceOf(AppException.class);
        engine.promptReady(p, p.getCurrentTurnId(), "en", start); assertThat(p.getPhase()).isEqualTo(OralExamPhase.SPEAKING);
    }
    /** Lỗi kỹ thuật STT retry cùng submission, không tự đánh dấu NO_ANSWER. */
    @Test void technicalFailureRetriesSameSubmission() {
        var p = create(1, 1); listen(p, start); engine.command(p, OralExamAction.SUBMIT, null, start, id -> "Frozen main");
        var submission = engine.activeSubmission(p).getId(); engine.failure(p, submission, start);
        assertThat(p.getPhase()).isEqualTo(OralExamPhase.TECHNICAL_ERROR); assertThat(p.getQuestions().getFirst().getStatus()).isEqualTo(OralQuestionStatus.ACTIVE);
        engine.command(p, OralExamAction.RETRY, null, start, id -> "Frozen main"); engine.transcript(p, submission, "Kết quả sau retry", start);
        assertThat(p.getSubmissions()).hasSize(1); assertThat(p.getPhase()).isEqualTo(OralExamPhase.DECIDING);
    }
    /** Lỗi AI sau STT không mất transcript và không gửi lại STT khi retry. */
    @Test void decisionFailurePreservesTranscript() {
        var p = create(1, 1); listen(p, start); var id = transcript(p, start, "Đã lưu rồi"); engine.failure(p, id, start);
        engine.command(p, OralExamAction.RETRY, null, start, ignored -> "Frozen main");
        assertThat(p.getPhase()).isEqualTo(OralExamPhase.DECIDING); assertThat(engine.currentTurn(p).getAnswerText()).isEqualTo("Đã lưu rồi");
    }
    /** Im lặng đến hết ngân sách được phân biệt với lỗi kỹ thuật và không tạo transcript rỗng. */
    @Test void silenceTimeoutProducesNoAnswerAndNextQuestion() {
        var p = create(2, 2); listen(p, start);
        assertThat(engine.synchronize(p, start.plusSeconds(90), start.plusMinutes(10), id -> "Frozen main")).isTrue();
        assertThat(p.getQuestions().getFirst().getStatus()).isEqualTo(OralQuestionStatus.NO_ANSWER);
        assertThat(p.getQuestions().getFirst().getOutcomeReason()).isEqualTo("ANSWER_TIME_EXHAUSTED");
        assertThat(p.getCurrentQuestionOrder()).isEqualTo(2); assertThat(p.getTurns().getFirst().getAnswerText()).isNull();
    }
    /** Follow-up hết giờ vẫn giữ câu trả lời chính làm bằng chứng chấm sau này. */
    @Test void timeoutOnFollowUpPreservesMainAnswer() {
        var p = create(1, 1); listen(p, start); var id = transcript(p, start, "Trả lời chính");
        engine.decision(p, id, true, "Hỏi tiếp?", start, ignored -> "Frozen main"); listen(p, start);
        engine.synchronize(p, start.plusSeconds(90), start.plusMinutes(10), ignored -> "Frozen main");
        assertThat(p.getPhase()).isEqualTo(OralExamPhase.COMPLETED); assertThat(p.getQuestions().getFirst().getStatus()).isEqualTo(OralQuestionStatus.ANSWERED);
    }
    /** Null ở snapshot không được ngầm đổi thành 90 giây từ form mô phỏng. */
    @Test void nullQuestionBudgetStillEndsAtAbsoluteSlotDeadline() {
        var p = create(1, 0); p.getQuestions().getFirst().setAnswerBudgetMs(null); listen(p, start);
        engine.synchronize(p, start.plusMinutes(2), start.plusMinutes(3), id -> "Frozen main"); assertThat(p.getPhase()).isEqualTo(OralExamPhase.ANSWERING);
        engine.synchronize(p, start.plusMinutes(3), start.plusMinutes(3), id -> "Frozen main"); assertThat(p.getPhase()).isEqualTo(OralExamPhase.EXPIRED);
    }
    /** Deadline thắng mọi bước, cả khi STT/AI chậm hoặc đang tạm dừng. */
    @ParameterizedTest @EnumSource(value = OralExamPhase.class, names = {"PREPARING","SPEAKING","ANSWERING","TRANSCRIBING","DECIDING","PAUSED","TECHNICAL_ERROR","REVIEW"})
    void deadlineExpiresEveryActivePhase(OralExamPhase phase) {
        var p = create(1, 1); p.setPhase(phase); p.setAnswerOpenedAt(phase == OralExamPhase.ANSWERING ? start : null);
        engine.synchronize(p, start.plusMinutes(10), start.plusMinutes(10), id -> "Frozen main");
        assertThat(p.getPhase()).isEqualTo(OralExamPhase.EXPIRED); assertThat(p.getAnswerOpenedAt()).isNull();
        assertThat(p.getTurns().getFirst().getAnswerText()).isNull();
    }
    /** Hết slot trong lúc lỗi STT không tự gán lỗi đó thành câu không biết trả lời. */
    @Test void expiryPreservesTechnicalOutcome() {
        var p = create(1, 1); listen(p, start); engine.command(p, OralExamAction.SUBMIT, null, start, id -> "Frozen main");
        engine.failure(p, engine.activeSubmission(p).getId(), start);
        engine.synchronize(p, start.plusMinutes(10), start.plusMinutes(10), id -> "Frozen main");
        assertThat(p.getQuestions().getFirst().getStatus()).isEqualTo(OralQuestionStatus.TECHNICAL_ERROR);
        assertThat(p.getSubmissions().getFirst().getStatus()).isEqualTo("CANCELLED");
    }
    /** Xác nhận nộp chỉ áp dụng ở hàng đợi bỏ qua, không cho kết thúc giữa lượt đang trả lời. */
    @Test void finishRequiresReviewAndClosesDeferredQuestionsWithoutFakeText() {
        var p = create(1, 0); listen(p, start);
        assertThatThrownBy(() -> engine.command(p, OralExamAction.FINISH, null, start, id -> "Frozen main")).isInstanceOf(AppException.class);
        engine.command(p, OralExamAction.DEFER, null, start, id -> "Frozen main");
        engine.command(p, OralExamAction.FINISH, null, start, id -> "Frozen main");
        assertThat(p.getPhase()).isEqualTo(OralExamPhase.COMPLETED); assertThat(p.getQuestions().getFirst().getOutcomeReason()).isEqualTo("SUBMITTED_UNANSWERED");
        assertThat(p.getTurns().getFirst().getAnswerText()).isNull();
    }
    /** Không bỏ qua follow-up đã có câu trả lời chính để sửa lại lịch sử. */
    @Test void cannotDeferFollowUpOrRevisitAnsweredQuestion() {
        var p = create(1, 1); listen(p, start); var id = transcript(p, start, "Câu chính"); engine.decision(p, id, true, "Tiếp?", start, ignored -> "Frozen main"); listen(p, start);
        assertThatThrownBy(() -> engine.command(p, OralExamAction.DEFER, null, start, ignored -> "Frozen main")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> engine.command(p, OralExamAction.REVISIT, 1, start, ignored -> "Frozen main")).isInstanceOf(AppException.class);
    }
    /** Callback sai mã hoặc nội dung không hợp lệ không thể trở thành bằng chứng. */
    @Test void invalidProviderResultsAreRejected() {
        var p = create(1, 1); listen(p, start); engine.command(p, OralExamAction.SUBMIT, null, start, id -> "Frozen main");
        assertThatThrownBy(() -> engine.transcript(p, UUID.randomUUID(), "Sai job", start)).isInstanceOf(AppException.class);
        var id = engine.activeSubmission(p).getId();
        assertThatThrownBy(() -> engine.transcript(p, id, "a\0b", start)).isInstanceOf(AppException.class);
        engine.transcript(p, id, "Hợp lệ", start);
        assertThatThrownBy(() -> engine.decision(p, id, true, " ", start, ignored -> "Frozen main")).isInstanceOf(AppException.class);
    }
    /** Nhiều lệnh nhỏ hơn 1ms không được biến thời gian đã trả lời thành miễn phí. */
    @Test void subMillisecondRequestsCannotExtendQuestionBudget() {
        var p = create(1, 0); listen(p, start);
        engine.synchronize(p, start.plusNanos(500_000), start.plusMinutes(10), id -> "Frozen main");
        engine.synchronize(p, start.plusNanos(1_000_000), start.plusMinutes(10), id -> "Frozen main");
        assertThat(p.getQuestions().getFirst().getAnswerUsedMs()).isEqualTo(2L);
    }
}
