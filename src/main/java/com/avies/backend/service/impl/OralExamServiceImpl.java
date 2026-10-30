package com.avies.backend.service.impl;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.OralExamResponse;
import com.avies.backend.entity.*;
import com.avies.backend.entity.enums.*;
import com.avies.backend.exception.*;
import com.avies.backend.repository.*;
import com.avies.backend.service.*;
import com.avies.backend.service.support.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Điều phối nguyên tử tiến trình thật; không gọi nhà cung cấp, tạo transcript giả hoặc thay đổi ngân hàng. */
@Service
@Validated
@RequiredArgsConstructor
@Transactional(timeout = 10)
public class OralExamServiceImpl implements OralExamService {
    /** Mỗi tab giữ quyền 45 giây, heartbeat chỉ gia hạn lease, không gia hạn bài thi. */ private static final int LEASE_SECONDS = 45;
    /** Kiểm tra quyền/khóa theo cùng giao thức Feature 2. */ private final OralExamAccessPolicy access;
    /** Persistence hội thoại tham gia transaction hiện tại. */ private final OralExamRepository repository;
    /** Bộ điều phối thuần không phụ thuộc mạng. */ private final OralExamCoordinator coordinator;
    /** Giữ nguyên Start/Finish đã được kiểm thử. */ private final ExamLifecycleService lifecycle;
    /** Đọc lại đúng nội dung snapshot, không lấy question bank hiện hành. */ private final StudentExamQuestionRepository questions;
    /** Đồng hồ máy chủ thay được trong kiểm thử. */ private final Clock clock;
    /** Metadata/claim audio, nullable cho các lượt cũ. */ private final OralAudioRepository audioRepository;
    /** File private được publish trong transaction ngắn có parent lock. */ private final OralAudioStore audioStore;
    /** Claim AI riêng; không dùng claim STT để quyết định hội thoại. */ private final OralDecisionRepository decisionRepository;
    /** Flag giúp kiểm tra cấu hình trước khi mở bài thi thật mới. */ private final com.avies.backend.config.OralDecisionProperties decisionSettings;
    /** Khả năng pipeline do BE xác định, không lấy từ FE. */ private final OralExamRuntimeService runtime;
    /** Chỉ đánh thức worker sau khi dữ liệu đã commit; không gọi mạng trong transaction. */ private final org.springframework.context.ApplicationEventPublisher events;
    /** Đồng bộ thay đổi JPA với JDBC trước khi kiểm tra bằng chứng Finish. */
    @PersistenceContext private EntityManager entityManager;

    /** Lệnh mở có thể retry mà không tạo đề/câu hỏi/lượt hội thoại mới. */
    @Override
    public OralExamResponse open(Long examId, OralExamStartRequest request) {
        return transaction(() -> {
            StudentExam exam = access.owned(examId);
            var existing = repository.findLocked(examId);
            if (existing.isPresent()) {
                var p = existing.get(); var before = Before.capture(p);
                normalize(exam, p, now());
                if (!coordinator.terminal(p)) claim(p, request.clientSessionId(), now());
                persist(exam, p, before); return response(exam, p);
            }
            if (decisionSettings.isEnabled() && !runtime.get().providersReady()) throw new AppException(ErrorCode.ORAL_DECISION_NOT_READY);
            lifecycle.start(examId);
            if (repository.hasLegacyConversation(examId)) throw new AppException(ErrorCode.ORAL_LEGACY_EVIDENCE);
            var source = sourceQuestions(examId);
            var timestamp = now();
            if (!timestamp.isBefore(exam.getScheduledEndAt())) throw new AppException(ErrorCode.EXAM_OUTSIDE_SLOT);
            var p = new OralExamProgress(); p.setStudentExamId(examId); p.setPhase(OralExamPhase.PREPARING);
            p.setRevision(0L); p.setUpdatedAt(timestamp); p.setFollowUpsUsed(0); p.setMaxFollowUps(exam.getSession().getMaxFollowUps());
            p.setMainCount(source.size()); p.setClientSessionId(request.clientSessionId()); p.setLeaseUntil(timestamp.plusSeconds(LEASE_SECONDS));
            for (var sourceQuestion : source) {
                var q = new OralExamProgress.Question(); q.setStudentExamQuestionId(sourceQuestion.getId()); q.setStudentExamId(examId);
                q.setQuestionOrder(sourceQuestion.getQuestionOrder()); q.setStatus(OralQuestionStatus.PENDING); q.setAnswerUsedMs(0L);
                Integer seconds = sourceQuestion.getSnapshotAnswerTimeLimitSeconds(); q.setAnswerBudgetMs(seconds == null ? null : seconds * 1000L);
                p.getQuestions().add(q);
            }
            coordinator.next(p, timestamp, prompt(source));
            repository.insert(p);
            persist(exam, p, new Before(Set.of(), Set.of(), Set.of()));
            return response(exam, p);
        });
    }

    /** GET có thể chốt timeout đã xảy ra; không khởi động hoặc cấp lại thời gian. */
    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ, timeout = 10)
    public OralExamResponse get(Long examId) {
        return transaction(() -> {
            var exam=access.readOwned(examId);
            var view=repository.findSnapshot(examId).orElseThrow(() -> new AppException(ErrorCode.ORAL_PROGRESS_NOT_FOUND));
            // Tính remaining trên bản đọc; không UPDATE mỗi poll hoặc cộng/trừ thời gian hai lần.
            if (!normalize(exam,view,now())) return response(exam,view);
            // Chỉ timeout thực sự mới vào đường ghi có khóa và đọc lại aggregate.
            exam=access.owned(examId); var p=required(examId); var before=Before.capture(p);
            normalize(exam,p,now()); persist(exam,p,before); return response(exam,p);
        });
    }

    /** Cùng client có thể gia hạn lease; tab khác không được takeover bằng heartbeat. */
    @Override public com.avies.backend.dto.response.OralLeaseResponse renewLease(Long examId, OralExamStartRequest request) {
        return transaction(() -> {
            access.readOwned(examId); var timestamp=now(); var until=timestamp.plusSeconds(LEASE_SECONDS);
            if (!repository.renewLease(examId,request.clientSessionId(),timestamp,until)) {
                if (repository.leaseEnded(examId,timestamp)) return new com.avies.backend.dto.response.OralLeaseResponse(timestamp,null,true);
                throw new AppException(ErrorCode.ORAL_ACTIVE_CLIENT);
            }
            return new com.avies.backend.dto.response.OralLeaseResponse(timestamp,until);
        });
    }

    /** Một requestId gắn một payload; lệnh cũ hoặc tab khác không được chuyển câu. */
    @Override
    public OralExamResponse execute(Long examId, OralExamCommandRequest request) {
        return transaction(() -> {
            if ((request.action() == OralExamAction.REVISIT) != (request.targetQuestionOrder() != null)) throw new AppException(ErrorCode.INVALID_REQUEST);
            var exam = access.owned(examId); var p = required(examId); var before = Before.capture(p);
            String hash = fingerprint(request);
            Optional<String> receipt = repository.commandHash(examId, request.requestId());
            if (receipt.isPresent() && !receipt.get().equals(hash)) throw new AppException(ErrorCode.ORAL_STALE_COMMAND);
            OffsetDateTime timestamp = now();
            boolean timedOut = normalize(exam, p, timestamp);
            if (coordinator.terminal(p)) { persist(exam, p, before); return response(exam, p); }
            if (receipt.isPresent()) {
                if (!p.getClientSessionId().equals(request.clientSessionId())) throw new AppException(ErrorCode.ORAL_ACTIVE_CLIENT);
                persist(exam, p, before); return response(exam, p);
            }
            if (timedOut) {
                // Thời gian thắng lệnh vừa đến; trả trạng thái mới, không gửi câu trả lời cho câu kế tiếp.
                persist(exam, p, before); return response(exam, p);
            }
            if (!Objects.equals(request.expectedRevision(), p.getRevision()) || !Objects.equals(request.turnId(), p.getCurrentTurnId()))
                throw new AppException(ErrorCode.ORAL_STALE_COMMAND);
            if (request.action() == OralExamAction.HEARTBEAT) {
                // Heartbeat chỉ giữ quyền của chính tab; takeover phải là hành động mở/RESUME rõ ràng.
                if (!Objects.equals(p.getClientSessionId(), request.clientSessionId())) throw new AppException(ErrorCode.ORAL_ACTIVE_CLIENT);
                claim(p, request.clientSessionId(), timestamp);
            } else if (request.action() == OralExamAction.RESUME) claim(p, request.clientSessionId(), timestamp);
            else if (!Objects.equals(p.getClientSessionId(), request.clientSessionId()) || !timestamp.isBefore(p.getLeaseUntil()))
                throw new AppException(ErrorCode.ORAL_ACTIVE_CLIENT);
            p.setLeaseUntil(timestamp.plusSeconds(LEASE_SECONDS));
            if (request.action() != OralExamAction.RESUME || p.getPhase() == OralExamPhase.PAUSED)
                coordinator.command(p, request.action(), request.targetQuestionOrder(), timestamp, prompt(sourceQuestions(examId)));
            repository.rememberCommand(examId, request.requestId(), hash, timestamp);
            persist(exam, p, before); return response(exam, p);
        });
    }

    /** Không giữ transaction/khóa DB trong lúc gọi TTS; không lấy câu tiếp theo hoặc câu đã thi xong. */
    @Override public OralSpeechPlan speechPlan(Long examId, OralSpeechRequest request) {
        return transaction(() -> {
            var exam = access.owned(examId); var p = required(examId); var before = Before.capture(p);
            normalize(exam, p, now());
            checkSpeech(p, request);
            var turn = coordinator.currentTurn(p);
            var plan = OralSpeechPlan.from(turn.getId(), turn.getLanguage(), p.getCurrentQuestionOrder(), turn.getFollowUpIndex(), turn.getPromptText());
            persist(exam, p, before); return plan;
        });
    }

    /** Callback gắn với request đã xác thực; lượt đã đổi trong khi gọi mạng thì bỏ kết quả. */
    @Override public void confirmSpeech(Long examId, OralSpeechRequest request, String language) {
        transaction(() -> {
            var exam = access.owned(examId); var p = required(examId); var before = Before.capture(p);
            normalize(exam, p, now()); checkSpeech(p, request);
            coordinator.promptReady(p, request.turnId(), language, now());
            persist(exam, p, before); return null;
        });
    }

    /** Retry audio cùng turn sau khi PREPARING → SPEAKING được phép; không nhận revision tương lai. */
    private void checkSpeech(OralExamProgress p, OralSpeechRequest request) {
        if (!Objects.equals(p.getClientSessionId(), request.clientSessionId()) || !now().isBefore(p.getLeaseUntil()))
            throw new AppException(ErrorCode.ORAL_ACTIVE_CLIENT);
        if (!Objects.equals(p.getCurrentTurnId(), request.turnId()) || request.expectedRevision() > p.getRevision()
                || p.getPhase() == OralExamPhase.PREPARING && !Objects.equals(request.expectedRevision(), p.getRevision()))
            throw new AppException(ErrorCode.ORAL_STALE_COMMAND);
        if (p.getPhase() != OralExamPhase.PREPARING && p.getPhase() != OralExamPhase.SPEAKING)
            throw new AppException(ErrorCode.ORAL_ACTION_NOT_ALLOWED);
    }

    /** Hash cùng submission là receipt upload; không cho thay bytes đã nhận bằng câu trả lời mới. */
    @Override public OralExamResponse attachAudio(Long examId, UUID job, OralAudioRequest request, OralAudioPayload audio) {
        return transaction(() -> {
            var exam = access.owned(examId); var p = required(examId); var before = Before.capture(p);
            audioRepository.requireReady(); normalize(exam, p, now());
            var s = p.getSubmissions().stream().filter(row -> row.getId().equals(job) && row.getTurnId().equals(request.turnId())).findFirst()
                    .orElseThrow(() -> new AppException(ErrorCode.ORAL_STALE_COMMAND));
            if (s.getAudioSha256() != null) {
                if (!s.getAudioSha256().equals(audio.sha256())) throw new AppException(ErrorCode.ORAL_AUDIO_CONFLICT);
                // Retry response bị mất không tạo submission hoặc gọi provider lần nữa.
                persist(exam, p, before); return response(exam, p);
            }
            if (!Objects.equals(p.getClientSessionId(), request.clientSessionId()) || !now().isBefore(p.getLeaseUntil())) throw new AppException(ErrorCode.ORAL_ACTIVE_CLIENT);
            if (!Objects.equals(p.getCurrentTurnId(), request.turnId()) || p.getPhase() != OralExamPhase.TRANSCRIBING || !"PROCESSING".equals(s.getStatus()))
                throw new AppException(ErrorCode.ORAL_ACTION_NOT_ALLOWED);
            audioStore.save(job, audio);
            String storage = audioStore.storageId();
            audioRepository.attach(job, audio.sha256(), audio.mediaType(), audio.bytes().length, now(), storage); s.setAudioSha256(audio.sha256()); s.setAudioStorageId(storage);
            persist(exam, p, before);
            events.publishEvent(new OralWorkReady(examId,job,OralWorkReady.Stage.STT));
            return response(exam, p);
        });
    }

    /** Giữ cùng giao thức khóa Feature 2; không giữ transaction trong lúc gửi audio ra Groq. */
    @Override public OralTranscriptionWork claimTranscription(Long examId, UUID job) {
        return transaction(() -> {
            var exam = access.internal(examId); var p = required(examId); var before = Before.capture(p);
            audioRepository.requireReady(); normalize(exam, p, now());
            var s = p.getSubmissions().stream().filter(row -> row.getId().equals(job)).findFirst().orElseThrow();
            if (p.getPhase() != OralExamPhase.TRANSCRIBING || !Objects.equals(p.getCurrentTurnId(), s.getTurnId()) || !"PROCESSING".equals(s.getStatus()) || s.getAudioSha256() == null) {
                persist(exam, p, before); return null;
            }
            if (!Objects.equals(s.getAudioStorageId(), audioStore.storageId())) return null;
            UUID claim = UUID.randomUUID();
            if (!audioRepository.claim(job, claim, now())) return null;
            persist(exam, p, before);
            return new OralTranscriptionWork(examId, job, claim, coordinator.currentTurn(p).getLanguage(), s.getAudioSha256());
        });
    }

    /** Nonce/slot thắng kết quả STT; kỹ thuật lỗi giữ đúng job để Student có thể RETRY. */
    @Override public void completeTranscription(OralTranscriptionWork work, String transcript, Integer errorCode) {
        transaction(() -> {
            var exam = access.internal(work.examId()); var p = required(work.examId()); var before = Before.capture(p);
            audioRepository.requireReady(); boolean timedOut = normalize(exam, p, now());
            if (!audioRepository.ownsClaim(work.submissionId(), work.claimId(), now())) { persist(exam, p, before); return null; }
            if (!timedOut && !coordinator.terminal(p) && p.getPhase() == OralExamPhase.TRANSCRIBING) {
                if (errorCode == null) coordinator.transcript(p, work.submissionId(), transcript, now());
                else {
                    if (!Set.of(1810,1811,1812,1813).contains(errorCode)) throw new AppException(ErrorCode.ORAL_PROVIDER_RESULT_INVALID);
                    coordinator.failure(p, work.submissionId(), now());
                    p.getSubmissions().stream().filter(s -> s.getId().equals(work.submissionId())).forEach(s -> s.setSttErrorCode(errorCode));
                }
            }
            audioRepository.release(work.submissionId(), work.claimId(), errorCode);
            persist(exam, p, before);
            if (p.getPhase()==OralExamPhase.DECIDING && errorCode==null)
                events.publishEvent(new OralWorkReady(work.examId(),work.submissionId(),OralWorkReady.Stage.DECISION));
            return null;
        });
    }

    /** Lấy context của đúng câu snapshot và giành claim ngắn trước khi gọi Gemini. */
    @Override public OralDecisionWork claimDecision(Long examId, UUID job) {
        return transaction(() -> {
            var exam=access.internal(examId); var p=required(examId); var before=Before.capture(p);
            decisionRepository.requireReady(); normalize(exam,p,now());
            var s=p.getSubmissions().stream().filter(row -> row.getId().equals(job)).findFirst().orElseThrow();
            if (p.getPhase()!=OralExamPhase.DECIDING || !Objects.equals(p.getCurrentTurnId(),s.getTurnId()) || !"TRANSCRIBED".equals(s.getStatus()) || s.getTranscriptText()==null) {
                persist(exam,p,before); return null;
            }
            UUID claim=UUID.randomUUID();
            if (!decisionRepository.claim(job,claim,now())) return null;
            var turn=coordinator.currentTurn(p);
            var source=sourceQuestions(examId).stream().filter(q -> q.getId().equals(turn.getStudentExamQuestionId())).findFirst().orElseThrow();
            var history=p.getTurns().stream().filter(t -> t.getStudentExamQuestionId().equals(turn.getStudentExamQuestionId()) && t.getAnswerText()!=null)
                    .sorted(Comparator.comparing(OralExamProgress.Turn::getFollowUpIndex)).toList();
            // Bounded context: câu chính riêng, tối đa tám lượt gần nhất; không cắt transcript từng lượt.
            var recent=history.subList(Math.max(0,history.size()-8),history.size()).stream()
                    .map(t -> new OralDecisionWork.Exchange(t.getPromptText(),t.getAnswerText())).toList();
            Long remaining=coordinator.remaining(coordinator.currentQuestion(p));
            boolean canFollow=p.getFollowUpsUsed()<p.getMaxFollowUps() && (remaining==null || remaining>0);
            persist(exam,p,before);
            return new OralDecisionWork(examId,job,claim,turn.getLanguage(),source.getSnapshotContent(),recent,canFollow);
        });
    }

    /** Deadline/nonce/turn thắng callback; chỉ quyết định hợp lệ mới sinh follow-up hoặc kết thúc câu. */
    @Override public void completeDecision(OralDecisionWork work, OralDecisionResult result, Integer errorCode) {
        transaction(() -> {
            var exam=access.internal(work.examId()); var p=required(work.examId()); var before=Before.capture(p);
            decisionRepository.requireReady(); boolean timedOut=normalize(exam,p,now());
            if (!decisionRepository.ownsClaim(work.submissionId(),work.claimId(),now())) { persist(exam,p,before); return null; }
            var s=p.getSubmissions().stream().filter(row -> row.getId().equals(work.submissionId())).findFirst().orElseThrow();
            if (!timedOut && !coordinator.terminal(p) && p.getPhase()==OralExamPhase.DECIDING && "TRANSCRIBED".equals(s.getStatus()) && Objects.equals(s.getTurnId(),p.getCurrentTurnId())) {
                if (errorCode==null) {
                    if (result==null || !result.followUp() && result.prompt()!=null) throw new AppException(ErrorCode.ORAL_PROVIDER_RESULT_INVALID);
                    boolean follow=result.followUp() && p.getFollowUpsUsed()<p.getMaxFollowUps() && (coordinator.remaining(coordinator.currentQuestion(p))==null || coordinator.remaining(coordinator.currentQuestion(p))>0);
                    coordinator.decision(p,work.submissionId(),follow,follow ? result.prompt() : null,now(),prompt(sourceQuestions(work.examId())));
                } else {
                    if (!Set.of(1815,1816,1817).contains(errorCode)) throw new AppException(ErrorCode.ORAL_PROVIDER_RESULT_INVALID);
                    coordinator.failure(p,work.submissionId(),now()); s.setDecisionErrorCode(errorCode);
                }
            }
            decisionRepository.release(work.submissionId(),work.claimId(),errorCode);
            persist(exam,p,before); return null;
        });
    }

    /** TTS chỉ xác nhận đúng turnId; ngôn ngữ không do Student quyết định. */
    @Override
    public OralExamResponse promptReady(Long examId, UUID turnId, String language) {
        return worker(examId, (p, timestamp) -> coordinator.promptReady(p, turnId, language, timestamp));
    }
    /** Callback rỗng không tạo transcript; callback lặp không thêm bằng chứng. */
    @Override
    public OralExamResponse transcriptReady(Long examId, UUID submissionId, String transcript) {
        return worker(examId, (p, timestamp) -> coordinator.transcript(p, submissionId, transcript, timestamp));
    }
    /** Tách gọi AI bên ngoài transaction; chỉ kết quả được xác thực mới đi qua hàm này. */
    @Override
    public OralExamResponse decide(Long examId, UUID submissionId, boolean followUp, String followUpPrompt) {
        return worker(examId, (p, timestamp) -> coordinator.decision(p, submissionId, followUp, followUpPrompt, timestamp, prompt(sourceQuestions(examId))));
    }
    /** Ghi lỗi mà không tự chuyển câu, cộng follow-up hoặc ghi transcript giả. */
    @Override
    public OralExamResponse technicalFailure(Long examId, UUID workId) {
        return worker(examId, (p, timestamp) -> coordinator.failure(p, workId, timestamp));
    }

    /** Callback nội bộ vẫn lấy cùng bộ khóa và kiểm tra deadline trước khi sửa dữ liệu. */
    private OralExamResponse worker(long examId, BiConsumer<OralExamProgress,OffsetDateTime> callback) {
        return transaction(() -> {
            var exam = access.internal(examId); var p = required(examId); var before = Before.capture(p);
            OffsetDateTime timestamp = now();
            boolean timeout = normalize(exam, p, timestamp);
            if (!timeout && !coordinator.terminal(p)) callback.accept(p, timestamp);
            persist(exam, p, before); return response(exam, p);
        });
    }

    /** Deadline/Close luôn thắng callback; không biến trạng thái cuối thành IN_PROGRESS. */
    private boolean normalize(StudentExam exam, OralExamProgress p, OffsetDateTime timestamp) {
        if (exam.getScheduledStartAt() == null || exam.getScheduledEndAt() == null || exam.getStartedAt() == null || exam.getStartedAt().isAfter(timestamp)
                || timestamp.isBefore(exam.getScheduledStartAt())) throw new AppException(ErrorCode.EXAM_LIFECYCLE_INCONSISTENT);
        if (coordinator.terminal(p)) {
            if ((p.getPhase() == OralExamPhase.COMPLETED && exam.getStatus() != StudentExamStatus.COMPLETED)
                    || (p.getPhase() == OralExamPhase.EXPIRED && exam.getStatus() != StudentExamStatus.EXPIRED))
                throw new AppException(ErrorCode.EXAM_LIFECYCLE_INCONSISTENT);
            return false;
        }
        if (exam.getStatus() == StudentExamStatus.EXPIRED) {
            coordinator.expire(p); coordinator.changed(p, timestamp); return true;
        }
        if (exam.getStatus() != StudentExamStatus.IN_PROGRESS || exam.getSession().getStatus() != ExamSessionStatus.ONGOING)
            throw new AppException(ErrorCode.EXAM_LIFECYCLE_NOT_ALLOWED);
        return coordinator.synchronize(p, timestamp, exam.getScheduledEndAt(), id -> prompt(sourceQuestions(exam.getId())).apply(id));
    }

    /** Claim không đổi slot/ngân sách; tab mới phải chờ hết lease và nghe lại nếu đang trả lời. */
    private void claim(OralExamProgress p, UUID client, OffsetDateTime timestamp) {
        if (!Objects.equals(p.getClientSessionId(), client)) {
            if (timestamp.isBefore(p.getLeaseUntil())) throw new AppException(ErrorCode.ORAL_ACTIVE_CLIENT);
            if (p.getPhase() == OralExamPhase.ANSWERING || p.getPhase() == OralExamPhase.SPEAKING)
                coordinator.command(p, OralExamAction.PAUSE, null, timestamp, prompt(sourceQuestions(p.getStudentExamId())));
            p.setClientSessionId(client); coordinator.changed(p, timestamp);
        }
        p.setLeaseUntil(timestamp.plusSeconds(LEASE_SECONDS));
    }

    /** Ghi lượt mới, bằng chứng STT và state cùng transaction, rồi mới mở cổng Finish Feature 2. */
    private void persist(StudentExam exam, OralExamProgress p, Before before) {
        if (p.getPhase()==OralExamPhase.TECHNICAL_ERROR) {
            var s=p.getSubmissions().stream().filter(row -> row.getTurnId().equals(p.getCurrentTurnId()) && "FAILED".equals(row.getStatus())).findFirst().orElse(null);
            if (s!=null && p.getRecoveryPhase()==OralExamPhase.TRANSCRIBING && s.getSttErrorCode()!=null && audioRepository.ready()) audioRepository.fail(s.getId(),s.getSttErrorCode());
            if (s!=null && p.getRecoveryPhase()==OralExamPhase.DECIDING && s.getDecisionErrorCode()!=null && decisionRepository.ready()) decisionRepository.fail(s.getId(),s.getDecisionErrorCode());
        }
        for (var turn : p.getTurns()) if (!before.turns().contains(turn.getId())) {
            repository.insertTurn(turn);
            if (p.getPhase()==OralExamPhase.PREPARING && turn.getId().equals(p.getCurrentTurnId()))
                events.publishEvent(new OralSpeechWarmup(OralSpeechPlan.from(turn.getId(),turn.getLanguage(),p.getCurrentQuestionOrder(),turn.getFollowUpIndex(),turn.getPromptText()),exam.getScheduledEndAt()));
        }
        for (var submission : p.getSubmissions()) if (!before.submissions().contains(submission.getId())) repository.insertSubmission(submission);
        for (var turn : p.getTurns()) {
            if (turn.getAnswerText() != null && !before.answered().contains(turn.getId())) {
                var submission = p.getSubmissions().stream().filter(s -> s.getTurnId().equals(turn.getId()) && s.getTranscriptText() != null).findFirst().orElseThrow();
                repository.insertAnswer(turn, submission.getId());
            }
        }
        repository.save(p);
        if (p.getPhase() == OralExamPhase.EXPIRED && exam.getStatus() == StudentExamStatus.IN_PROGRESS) exam.setStatus(StudentExamStatus.EXPIRED);
        if (p.getPhase() == OralExamPhase.COMPLETED && exam.getStatus() == StudentExamStatus.IN_PROGRESS) lifecycle.finishOral(exam.getId());
        entityManager.flush();
    }

    /** Chỉ lấy nội dung từ snapshot đã chốt; không cần đọc lại đáp án/rubric đang thay đổi. */
    private List<StudentExamQuestion> sourceQuestions(long examId) {
        var rows = questions.findByStudentExamIdOrderByQuestionOrderAscIdAsc(examId);
        if (rows.isEmpty() || rows.stream().anyMatch(q -> q.getSnapshotCapturedAt() == null || q.getSnapshotContent() == null || q.getSnapshotContent().isBlank()
                || q.getSnapshotAnswerTimeLimitSeconds() != null && q.getSnapshotAnswerTimeLimitSeconds() <= 0))
            throw new AppException(ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
        return rows;
    }
    /** Bản đồ chỉ nội bộ để tạo câu chính theo thứ tự; tuyệt đối không serialize cho Student. */
    private Function<Long,String> prompt(List<StudentExamQuestion> source) {
        Map<Long,String> content = source.stream().collect(Collectors.toMap(StudentExamQuestion::getId, StudentExamQuestion::getSnapshotContent));
        return id -> Optional.ofNullable(content.get(id)).orElseThrow(() -> new AppException(ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE));
    }
    /** Tiến trình thiếu không được tự backfill khi GET hoặc nhận callback. */
    private OralExamProgress required(long examId) { return repository.findLocked(examId).orElseThrow(() -> new AppException(ErrorCode.ORAL_PROGRESS_NOT_FOUND)); }
    /** DTO chỉ gồm metadata và transcript Student, không serialize aggregate/prompt nội bộ. */
    private OralExamResponse response(StudentExam exam, OralExamProgress p) {
        var turn = p.getCurrentTurnId() == null ? null : coordinator.currentTurn(p);
        UUID submission = p.getSubmissions().stream().filter(s -> Objects.equals(s.getTurnId(), p.getCurrentTurnId()) && Set.of("PROCESSING", "TRANSCRIBED", "FAILED").contains(s.getStatus())).map(OralExamProgress.Submission::getId).findFirst().orElse(null);
        Map<Long,Integer> orders = p.getQuestions().stream().collect(Collectors.toMap(OralExamProgress.Question::getStudentExamQuestionId, OralExamProgress.Question::getQuestionOrder));
        var transcript = p.getTurns().stream().filter(t -> t.getAnswerText() != null).sorted(Comparator.comparing((OralExamProgress.Turn t) -> t.getAnsweredAt()).thenComparing(t -> orders.get(t.getStudentExamQuestionId())).thenComparing(OralExamProgress.Turn::getFollowUpIndex))
                .map(t -> new OralExamResponse.Transcript(p.getQuestions().stream().filter(q -> q.getStudentExamQuestionId().equals(t.getStudentExamQuestionId())).findFirst().orElseThrow().getQuestionOrder(), t.getFollowUpIndex(), t.getAnswerText(), t.getAnsweredAt())).toList();
        var activeSubmission = p.getSubmissions().stream().filter(s -> Objects.equals(s.getId(), submission)).findFirst().orElse(null);
        return new OralExamResponse(exam.getId(), exam.getStatus(), p.getPhase(), p.getRevision(), now(), exam.getScheduledEndAt(), p.getLeaseUntil(), p.getMainCount(),
                p.getCurrentQuestionOrder(), p.getCurrentTurnId(), turn == null ? null : turn.getFollowUpIndex(), turn == null ? null : turn.getLanguage(), p.getFollowUpsUsed(), p.getMaxFollowUps(),
                turn == null ? null : coordinator.remaining(coordinator.currentQuestion(p)), submission,
                p.getQuestions().stream().map(q -> new OralExamResponse.Question(q.getQuestionOrder(), q.getStatus(), coordinator.remaining(q), q.getOutcomeReason())).toList(), transcript,
                activeSubmission != null && activeSubmission.getAudioSha256() != null, activeSubmission == null ? null : activeSubmission.getDecisionErrorCode()!=null ? activeSubmission.getDecisionErrorCode() : activeSubmission.getSttErrorCode());
    }
    /** Hash theo trường cố định, không phụ thuộc thứ tự thuộc tính JSON hoặc locale. */
    private String fingerprint(OralExamCommandRequest request) {
        String canonical = String.join("|", request.clientSessionId().toString(), request.expectedRevision().toString(),
                Objects.toString(request.turnId(), ""), request.action().name(), Objects.toString(request.targetQuestionOrder(), ""));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Độ chính xác microsecond khớp PostgreSQL, không làm timestamp retry lệch nhau. */
    private OffsetDateTime now() { return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS); }
    /** Chuẩn hóa các lỗi khóa/ràng buộc, không che lỗi lập trình thành thành công. */
    private <T> T transaction(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException | PersistenceException exception) {
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable cause = exception; cause != null && seen.add(cause); cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getSQLState() != null && (sql.getSQLState().startsWith("23") || Set.of("55P03", "40001", "40P01", "57014").contains(sql.getSQLState())))
                    throw new AppException(ErrorCode.EXAM_SESSION_CONFLICT);
            }
            throw exception;
        }
    }
    /** Snapshot ID trước mutation để ghi thêm bằng chứng đúng một lần. */
    private record Before(Set<UUID> turns, Set<UUID> submissions, Set<UUID> answered) {
        /** Chỉ lưu ID, không sao chép nội dung đề/đáp án. */
        static Before capture(OralExamProgress p) {
            return new Before(p.getTurns().stream().map(OralExamProgress.Turn::getId).collect(Collectors.toSet()),
                    p.getSubmissions().stream().map(OralExamProgress.Submission::getId).collect(Collectors.toSet()),
                    p.getTurns().stream().filter(t -> t.getAnswerText() != null).map(OralExamProgress.Turn::getId).collect(Collectors.toSet()));
        }
    }
}
