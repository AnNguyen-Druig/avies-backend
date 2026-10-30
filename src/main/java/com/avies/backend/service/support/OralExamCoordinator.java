package com.avies.backend.service.support;

import com.avies.backend.entity.OralExamProgress;
import com.avies.backend.entity.enums.*;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;

/** Máy trạng thái thuần: không gọi AI, repository hoặc tin đồng hồ/ngân sách do Student gửi. */
@Component
public class OralExamCoordinator {
    /** Đồng bộ đồng hồ trước mọi thao tác; true nghĩa là đã đổi câu hoặc kết thúc do thời gian. */
    public boolean synchronize(OralExamProgress p, OffsetDateTime now, OffsetDateTime deadline, Function<Long,String> prompt) {
        if (terminal(p)) return false;
        if (p.getPhase() == OralExamPhase.ANSWERING) {
            var q = currentQuestion(p);
            OffsetDateTime end = now.isAfter(deadline) ? deadline : now;
            Duration duration = Duration.between(p.getAnswerOpenedAt(), end);
            // Làm tròn lên phần lẻ dưới millisecond để nhiều heartbeat không làm tăng ngân sách.
            long elapsed = duration.isNegative() ? 0 : duration.toMillis() + (duration.getNano() % 1_000_000 == 0 ? 0 : 1);
            q.setAnswerUsedMs(q.getAnswerBudgetMs() == null ? Math.addExact(q.getAnswerUsedMs(), elapsed)
                    : Math.min(q.getAnswerBudgetMs(), q.getAnswerUsedMs() + elapsed));
            p.setAnswerOpenedAt(end);
        }
        if (!now.isBefore(deadline)) { expire(p); changed(p, now); return true; }
        if (p.getPhase()==OralExamPhase.TRANSCRIBING || p.getPhase()==OralExamPhase.DECIDING) {
            var s=activeSubmission(p);
            if (!now.isBefore(s.getUpdatedAt().plusSeconds(90))) {
                boolean stt=p.getPhase()==OralExamPhase.TRANSCRIBING;
                failure(p,s.getId(),now);
                if (stt) s.setSttErrorCode(1811); else s.setDecisionErrorCode(1816);
                return true;
            }
        }
        if (p.getPhase() == OralExamPhase.ANSWERING && remaining(currentQuestion(p)) != null && remaining(currentQuestion(p)) == 0) {
            currentTurn(p).setAnswerStatus("NO_ANSWER");
            resolveQuestion(p, "ANSWER_TIME_EXHAUSTED");
            next(p, now, prompt); changed(p, now); return true;
        }
        return false;
    }

    /** Lệnh hợp lệ áp dụng đúng một bước; kiểm soát revision/lease nằm ở tầng điều phối transaction. */
    public void command(OralExamProgress p, OralExamAction action, Integer target, OffsetDateTime now, Function<Long,String> prompt) {
        if (action == OralExamAction.HEARTBEAT) return;
        switch (action) {
            case PROMPT_FINISHED -> {
                require(p.getPhase() == OralExamPhase.SPEAKING && Boolean.TRUE.equals(currentTurn(p).getPromptReady()));
                p.setPhase(OralExamPhase.ANSWERING); p.setAnswerOpenedAt(now);
            }
            case REPLAY -> {
                require(p.getPhase() == OralExamPhase.ANSWERING);
                p.setAnswerOpenedAt(null); p.setPhase(OralExamPhase.SPEAKING);
            }
            case DEFER -> {
                require(p.getPhase() == OralExamPhase.ANSWERING && currentTurn(p).getFollowUpIndex() == 0 && currentTurn(p).getAnswerText() == null);
                currentQuestion(p).setStatus(OralQuestionStatus.DEFERRED);
                p.setAnswerOpenedAt(null); next(p, now, prompt);
            }
            case REVISIT -> {
                require(p.getPhase() == OralExamPhase.REVIEW && target != null);
                var q = p.getQuestions().stream().filter(row -> row.getQuestionOrder().equals(target) && row.getStatus() == OralQuestionStatus.DEFERRED)
                        .findFirst().orElseThrow(() -> new AppException(ErrorCode.ORAL_ACTION_NOT_ALLOWED));
                var t = p.getTurns().stream().filter(row -> row.getStudentExamQuestionId().equals(q.getStudentExamQuestionId()) && row.getFollowUpIndex() == 0).findFirst().orElseThrow();
                q.setStatus(OralQuestionStatus.ACTIVE); p.setCurrentQuestionOrder(target); p.setCurrentTurnId(t.getId());
                p.setPhase(Boolean.TRUE.equals(t.getPromptReady()) ? OralExamPhase.SPEAKING : OralExamPhase.PREPARING);
            }
            case SUBMIT -> {
                require(p.getPhase() == OralExamPhase.ANSWERING);
                var s = new OralExamProgress.Submission();
                s.setId(UUID.randomUUID()); s.setStudentExamId(p.getStudentExamId()); s.setTurnId(p.getCurrentTurnId()); s.setStatus("PROCESSING"); s.setUpdatedAt(now);
                p.getSubmissions().add(s); currentTurn(p).setAnswerStatus("PROCESSING");
                p.setAnswerOpenedAt(null); p.setPhase(OralExamPhase.TRANSCRIBING);
            }
            case PAUSE -> {
                require(p.getPhase() == OralExamPhase.SPEAKING || p.getPhase() == OralExamPhase.ANSWERING);
                p.setRecoveryPhase(OralExamPhase.SPEAKING); p.setAnswerOpenedAt(null); p.setPhase(OralExamPhase.PAUSED);
            }
            case RESUME -> {
                require(p.getPhase() == OralExamPhase.PAUSED);
                p.setPhase(p.getRecoveryPhase()); p.setRecoveryPhase(null);
            }
            case RETRY -> {
                require(p.getPhase() == OralExamPhase.TECHNICAL_ERROR);
                p.setPhase(p.getRecoveryPhase()); p.setRecoveryPhase(null);
                if (p.getPhase() == OralExamPhase.TRANSCRIBING) activeSubmission(p).setStatus("PROCESSING");
                if (p.getPhase() == OralExamPhase.DECIDING) activeSubmission(p).setStatus("TRANSCRIBED");
                if (p.getPhase()==OralExamPhase.TRANSCRIBING || p.getPhase()==OralExamPhase.DECIDING) activeSubmission(p).setUpdatedAt(now);
            }
            case FINISH -> {
                require(p.getPhase() == OralExamPhase.REVIEW);
                p.getQuestions().stream().filter(q -> q.getStatus() == OralQuestionStatus.DEFERRED).forEach(q -> {
                    q.setStatus(OralQuestionStatus.NO_ANSWER); q.setOutcomeReason("SUBMITTED_UNANSWERED");
                    p.getTurns().stream().filter(t -> t.getStudentExamQuestionId().equals(q.getStudentExamQuestionId()) && t.getAnswerText() == null).forEach(t -> t.setAnswerStatus("NO_ANSWER"));
                });
                p.setPhase(OralExamPhase.COMPLETED); p.setCurrentTurnId(null); p.setCurrentQuestionOrder(null);
            }
            default -> throw new AppException(ErrorCode.ORAL_ACTION_NOT_ALLOWED);
        }
        changed(p, now);
    }

    /** Worker TTS xác định ngôn ngữ; follow-up luôn dùng ngôn ngữ của câu chính, không tự dịch snapshot. */
    public void promptReady(OralExamProgress p, UUID turnId, String language, OffsetDateTime now) {
        provider("vi".equals(language) || "en".equals(language));
        var turn = p.getTurns().stream().filter(t -> t.getId().equals(turnId)).findFirst().orElseThrow(() -> new AppException(ErrorCode.ORAL_PROVIDER_RESULT_INVALID));
        if (Boolean.TRUE.equals(turn.getPromptReady())) { provider(Objects.equals(turn.getLanguage(), language)); return; }
        require(p.getPhase() == OralExamPhase.PREPARING && turnId.equals(p.getCurrentTurnId()));
        if (turn.getFollowUpIndex() > 0) provider(Objects.equals(turn.getLanguage(), language));
        turn.setLanguage(language); turn.setPromptReady(true); p.setPhase(OralExamPhase.SPEAKING); changed(p, now);
    }

    /** STT rỗng trả lại cùng lượt; STT hợp lệ được lưu trước khi quyết định follow-up. */
    public void transcript(OralExamProgress p, UUID submissionId, String content, OffsetDateTime now) {
        var submission = submission(p, submissionId);
        String text = content == null ? "" : content.strip();
        provider(text.length() <= 20000 && text.indexOf('\0') < 0);
        if (Set.of("TRANSCRIBED", "SUCCEEDED").contains(submission.getStatus())) { provider(Objects.equals(text, submission.getTranscriptText())); return; }
        if ("EMPTY".equals(submission.getStatus())) { provider(text.isEmpty()); return; }
        require(p.getPhase() == OralExamPhase.TRANSCRIBING && "PROCESSING".equals(submission.getStatus()) && submission.getTurnId().equals(p.getCurrentTurnId()));
        var turn = currentTurn(p);
        if (text.isEmpty()) {
            submission.setStatus("EMPTY"); turn.setAnswerStatus("PENDING");
            p.setPhase(OralExamPhase.ANSWERING); p.setAnswerOpenedAt(now);
        } else {
            submission.setStatus("TRANSCRIBED"); submission.setTranscriptText(text);
            turn.setAnswerText(text); turn.setAnsweredAt(now); turn.setAnswerStatus("ANSWERED");
            p.setPhase(OralExamPhase.DECIDING);
        }
        submission.setUpdatedAt(now); changed(p, now);
    }

    /** BE áp giới hạn follow-up toàn bài, không nhận quyết định từ Student và không gọi AI trong transaction. */
    public void decision(OralExamProgress p, UUID submissionId, boolean followUp, String content, OffsetDateTime now, Function<Long,String> prompt) {
        var s = submission(p, submissionId);
        String decision = followUp ? "FOLLOW_UP" : "ADVANCE";
        String text = followUp && content != null ? content.strip() : null;
        provider(!followUp || text != null && !text.isBlank() && text.length() <= 10000 && text.indexOf('\0') < 0);
        if ("SUCCEEDED".equals(s.getStatus())) { provider(Objects.equals(s.getDecisionKind(), decision) && Objects.equals(s.getDecisionText(), text)); return; }
        require(p.getPhase() == OralExamPhase.DECIDING && "TRANSCRIBED".equals(s.getStatus()) && s.getTurnId().equals(p.getCurrentTurnId()));
        s.setDecisionKind(decision); s.setDecisionText(text); s.setStatus("SUCCEEDED"); s.setUpdatedAt(now);
        if (followUp && p.getFollowUpsUsed() < p.getMaxFollowUps() && (remaining(currentQuestion(p)) == null || remaining(currentQuestion(p)) > 0)) {
            var prior = currentTurn(p);
            addTurn(p, prior.getStudentExamQuestionId(), prior.getFollowUpIndex() + 1, text, prior.getLanguage(), now);
            p.setFollowUpsUsed(p.getFollowUpsUsed() + 1);
        } else { resolveQuestion(p, null); next(p, now, prompt); }
        changed(p, now);
    }

    /** Giữ nguyên bước lỗi và công việc để retry; không thêm transcript hoặc NO_ANSWER. */
    public void failure(OralExamProgress p, UUID workId, OffsetDateTime now) {
        if (p.getPhase() == OralExamPhase.TECHNICAL_ERROR) {
            provider(Objects.equals(p.getCurrentTurnId(), workId) || p.getSubmissions().stream().anyMatch(s -> s.getId().equals(workId) && s.getTurnId().equals(p.getCurrentTurnId())));
            return;
        }
        require(Set.of(OralExamPhase.PREPARING, OralExamPhase.TRANSCRIBING, OralExamPhase.DECIDING).contains(p.getPhase()));
        if (p.getPhase() == OralExamPhase.PREPARING) provider(Objects.equals(p.getCurrentTurnId(), workId));
        else { var s = activeSubmission(p); provider(s.getId().equals(workId)); s.setStatus("FAILED"); s.setUpdatedAt(now); }
        p.setRecoveryPhase(p.getPhase()); p.setPhase(OralExamPhase.TECHNICAL_ERROR); changed(p, now);
    }

    /** Chọn câu chưa đến lượt; nếu chỉ còn DEFERRED thì chờ Student chọn lại. */
    public void next(OralExamProgress p, OffsetDateTime now, Function<Long,String> prompt) {
        p.setAnswerOpenedAt(null); p.setRecoveryPhase(null);
        var next = p.getQuestions().stream().filter(q -> q.getStatus() == OralQuestionStatus.PENDING).findFirst();
        if (next.isPresent()) {
            var q = next.get(); q.setStatus(OralQuestionStatus.ACTIVE); p.setCurrentQuestionOrder(q.getQuestionOrder());
            addTurn(p, q.getStudentExamQuestionId(), 0, prompt.apply(q.getStudentExamQuestionId()), null, now);
        } else {
            p.setCurrentQuestionOrder(null); p.setCurrentTurnId(null);
            p.setPhase(p.getQuestions().stream().anyMatch(q -> q.getStatus() == OralQuestionStatus.DEFERRED) ? OralExamPhase.REVIEW : OralExamPhase.COMPLETED);
        }
    }

    /** Hết slot giữ bằng chứng và nguyên nhân; lỗi kỹ thuật không tự thành điểm không. */
    public void expire(OralExamProgress p) {
        OralExamPhase phase = p.getPhase();
        for (var q : p.getQuestions()) {
            if (q.getStatus() == OralQuestionStatus.ANSWERED || q.getStatus() == OralQuestionStatus.NO_ANSWER) continue;
            boolean active = q.getStatus() == OralQuestionStatus.ACTIVE;
            boolean technical = active && Set.of(OralExamPhase.PREPARING, OralExamPhase.SPEAKING, OralExamPhase.TRANSCRIBING, OralExamPhase.DECIDING, OralExamPhase.TECHNICAL_ERROR).contains(phase);
            boolean answered = p.getTurns().stream().anyMatch(t -> t.getStudentExamQuestionId().equals(q.getStudentExamQuestionId()) && t.getFollowUpIndex() == 0 && t.getAnswerText() != null);
            q.setOutcomeReason(technical ? "TECHNICAL_ERROR" : active ? "SLOT_EXPIRED" : q.getStatus() == OralQuestionStatus.DEFERRED ? "DEFERRED_AT_DEADLINE" : "NOT_REACHED");
            q.setStatus(answered ? OralQuestionStatus.ANSWERED : technical ? OralQuestionStatus.TECHNICAL_ERROR : OralQuestionStatus.NO_ANSWER);
        }
        p.getTurns().stream().filter(t -> t.getAnswerText() == null && !"NO_ANSWER".equals(t.getAnswerStatus())).forEach(t -> {
            var question = p.getQuestions().stream().filter(q -> q.getStudentExamQuestionId().equals(t.getStudentExamQuestionId())).findFirst().orElseThrow();
            t.setAnswerStatus("TECHNICAL_ERROR".equals(question.getOutcomeReason()) ? "TECHNICAL_ERROR" : "NO_ANSWER");
        });
        p.getSubmissions().stream().filter(s -> Set.of("PROCESSING", "TRANSCRIBED", "FAILED").contains(s.getStatus())).forEach(s -> s.setStatus("CANCELLED"));
        p.setPhase(OralExamPhase.EXPIRED); p.setAnswerOpenedAt(null); p.setRecoveryPhase(null); p.setCurrentTurnId(null); p.setCurrentQuestionOrder(null);
    }

    /** Lấy đúng câu hiện tại, không cho chọn trực tiếp câu đã trả lời. */
    public OralExamProgress.Question currentQuestion(OralExamProgress p) {
        return p.getQuestions().stream().filter(q -> q.getQuestionOrder().equals(p.getCurrentQuestionOrder())).findFirst().orElseThrow(() -> new AppException(ErrorCode.ORAL_ACTION_NOT_ALLOWED));
    }
    /** Mã lượt là nguồn đối chiếu callback và lệnh Student. */
    public OralExamProgress.Turn currentTurn(OralExamProgress p) {
        return p.getTurns().stream().filter(t -> t.getId().equals(p.getCurrentTurnId())).findFirst().orElseThrow(() -> new AppException(ErrorCode.ORAL_ACTION_NOT_ALLOWED));
    }
    /** Đọc công việc của lượt hiện tại; danh sách có thể chứa các lần STT rỗng đã kết thúc. */
    public OralExamProgress.Submission activeSubmission(OralExamProgress p) {
        return p.getSubmissions().stream().filter(s -> s.getTurnId().equals(p.getCurrentTurnId()) && Set.of("PROCESSING", "TRANSCRIBED", "FAILED").contains(s.getStatus()))
                .findFirst().orElseThrow(() -> new AppException(ErrorCode.ORAL_ACTION_NOT_ALLOWED));
    }
    /** Ngân sách null giữ nguyên ý nghĩa không có giới hạn riêng của snapshot. */
    public Long remaining(OralExamProgress.Question q) { return q.getAnswerBudgetMs() == null ? null : Math.max(0, q.getAnswerBudgetMs() - q.getAnswerUsedMs()); }
    /** Trạng thái cuối không nhận thao tác thay đổi bằng chứng. */
    public boolean terminal(OralExamProgress p) { return p.getPhase() == OralExamPhase.COMPLETED || p.getPhase() == OralExamPhase.EXPIRED; }
    /** Phiên bản tăng đúng khi có chuyển trạng thái nghiệp vụ. */
    public void changed(OralExamProgress p, OffsetDateTime now) { p.setRevision(p.getRevision() + 1); p.setUpdatedAt(now); }

    /** Tạo lượt hỏi mới trong aggregate, repository sẽ ghi cùng transaction. */
    private void addTurn(OralExamProgress p, long questionId, int index, String text, String language, OffsetDateTime now) {
        provider(text != null && !text.isBlank());
        var turn = new OralExamProgress.Turn(); turn.setId(UUID.randomUUID()); turn.setStudentExamId(p.getStudentExamId());
        turn.setStudentExamQuestionId(questionId); turn.setFollowUpIndex(index); turn.setPromptText(text); turn.setLanguage(language);
        turn.setPromptReady(false); turn.setAnswerStatus("PENDING"); turn.setCreatedAt(now); p.getTurns().add(turn);
        p.setCurrentTurnId(turn.getId()); p.setPhase(OralExamPhase.PREPARING);
    }
    /** Kết quả câu chính dựa vào bằng chứng chính, không dùng follow-up thay câu chính. */
    private void resolveQuestion(OralExamProgress p, String reason) {
        var q = currentQuestion(p);
        boolean answered = p.getTurns().stream().anyMatch(t -> t.getStudentExamQuestionId().equals(q.getStudentExamQuestionId()) && t.getFollowUpIndex() == 0 && t.getAnswerText() != null);
        q.setStatus(answered ? OralQuestionStatus.ANSWERED : OralQuestionStatus.NO_ANSWER); q.setOutcomeReason(reason);
    }
    /** Worker chỉ được hoàn tất công việc có trong aggregate của đúng lượt thi. */
    private OralExamProgress.Submission submission(OralExamProgress p, UUID id) {
        return p.getSubmissions().stream().filter(s -> s.getId().equals(id)).findFirst().orElseThrow(() -> new AppException(ErrorCode.ORAL_PROVIDER_RESULT_INVALID));
    }
    /** Lỗi chuyển bước được chuẩn hóa, không lộ dữ liệu đề. */
    private void require(boolean condition) { if (!condition) throw new AppException(ErrorCode.ORAL_ACTION_NOT_ALLOWED); }
    /** Kết quả nhà cung cấp phải hợp lệ trước khi ghi bằng chứng. */
    private void provider(boolean condition) { if (!condition) throw new AppException(ErrorCode.ORAL_PROVIDER_RESULT_INVALID); }
}
