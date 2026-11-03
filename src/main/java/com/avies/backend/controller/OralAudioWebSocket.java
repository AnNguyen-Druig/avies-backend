package com.avies.backend.controller;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.dto.request.*;
import com.avies.backend.entity.enums.OralExamAction;
import com.avies.backend.exception.*;
import com.avies.backend.service.*;
import com.avies.backend.service.support.*;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import tools.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Một socket/một lượt trả lời. Chỉ Azure SDK được tạo transcript; AUTH không nằm trong URL/log. */
@Component @lombok.extern.slf4j.Slf4j
public class OralAudioWebSocket extends AbstractWebSocketHandler {
    private final OralExamService oral;
    private final StreamingTranscriptionService stt;
    private final CustomJwtDecoder decoder;
    private final Validator validator;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final ExecutorService workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> daemon(task, "oral-stream"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "oral-stream-check"));

    public OralAudioWebSocket(OralExamService oral, StreamingTranscriptionService stt, @Lazy CustomJwtDecoder decoder, Validator validator, Clock clock) {
        this.oral = oral; this.stt = stt; this.decoder = decoder; this.validator = validator; this.clock = clock;
        var authorities = new JwtGrantedAuthoritiesConverter(); authorities.setAuthorityPrefix(""); authorities.setAuthoritiesClaimDelimiter(",");
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        watchdog.scheduleWithFixedDelay(this::check, 1, 1, TimeUnit.SECONDS);
    }

    @Override public synchronized void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        socket.setTextMessageSizeLimit(16384); socket.setBinaryMessageSizeLimit(64000);
        if (clients.size() >= 8 || socket.getUri() == null || socket.getUri().getRawQuery() != null) { socket.close(CloseStatus.POLICY_VIOLATION); return; }
        String path = socket.getUri().getPath();
        if (!path.matches(".*/student-exams/[1-9][0-9]{0,15}/oral-examination/stream")) { socket.close(CloseStatus.POLICY_VIOLATION); return; }
        long examId = Long.parseLong(path.substring(path.indexOf("/student-exams/") + 15).split("/")[0]);
        clients.put(socket.getId(), new Client(examId, new ConcurrentWebSocketSessionDecorator(socket, 1000, 128 * 1024)));
    }

    @Override protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        Client c = clients.get(socket.getId()); if (c == null) return;
        try {
            var root = json.readTree(message.getPayload());
            if (!root.isObject() || !root.path("type").isString()) throw invalid();
            switch (root.path("type").asText()) {
                case "AUTH" -> {
                    fields(root, Set.of("type", "token"));
                    synchronized (c) {
                        if (c.auth != null || !root.path("token").isString() || root.path("token").asText().length() > 12000) throw invalid();
                        var jwt = decoder.decode(root.path("token").asText());
                        if (jwt.getExpiresAt() == null || !clock.instant().isBefore(jwt.getExpiresAt())) throw new AppException(ErrorCode.UNAUTHENTICATED);
                        c.auth = converter.convert(jwt);
                    }
                    send(c, Map.of("type", "AUTHENTICATED"));
                }
                case "START" -> {
                    fields(root, Set.of("type", "request"));
                    var request = json.treeToValue(root.path("request"), OralSpeechRequest.class);
                    if (!validator.validate(request).isEmpty()) throw invalid();
                    synchronized (c) { authenticated(c); if (c.start != null) throw invalid(); c.start = request; c.working = true; }
                    run(c, () -> {
                        String language = as(c, () -> oral.streamingLanguage(c.examId, request));
                        synchronized (c) {
                            if (c.closed) return;
                            c.stream = stt.open(language, update -> send(c, Map.of("type", update.kind().name(), "segmentIndex", update.segmentIndex(), "text", update.text())));
                            c.working = false;
                        }
                        send(c, Map.of("type", "READY", "sampleRate", 16000));
                    });
                }
                case "FINISH" -> {
                    fields(root, Set.of("type", "command"));
                    var command = json.treeToValue(root.path("command"), OralExamCommandRequest.class);
                    if (!validator.validate(command).isEmpty() || command.action() != OralExamAction.SUBMIT || command.targetQuestionOrder() != null) throw invalid();
                    synchronized (c) {
                        authenticated(c);
                        if (c.stream == null || c.working || c.finishing || !Objects.equals(command.clientSessionId(), c.start.clientSessionId())
                                || !Objects.equals(command.turnId(), c.start.turnId())) throw invalid();
                        c.finishing = true; c.working = true;
                    }
                    run(c, () -> finish(c, command));
                }
                default -> throw invalid();
            }
        } catch (Exception failure) { fail(c, code(failure)); }
    }

    @Override protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
        Client c = clients.get(socket.getId()); if (c == null) return;
        try {
            synchronized (c) {
                authenticated(c);
                if (c.stream == null || c.working || c.finishing || c.audio.size() + message.getPayloadLength() + 44 > OralAudioPayload.MAX_BYTES) throw invalid();
                var frame = new byte[message.getPayloadLength()]; message.getPayload().get(frame);
                c.stream.write(frame); c.audio.writeBytes(frame);
            }
        } catch (Exception failure) { fail(c, code(failure)); }
    }

    private void finish(Client c, OralExamCommandRequest command) {
        long started = System.nanoTime();
        var reserved = as(c, () -> oral.execute(c.examId, command));
        if (reserved.phase() != com.avies.backend.entity.enums.OralExamPhase.TRANSCRIBING || !Objects.equals(reserved.currentTurnId(), command.turnId())) {
            send(c, Map.of("type", "RESULT", "state", reserved)); close(c); return;
        }
        var result = c.stream.finish().toCompletableFuture();
        try {
            var finalText = result.get(18, TimeUnit.SECONDS);
            byte[] pcm; synchronized (c) { pcm = c.audio.toByteArray(); }
            var audio = OralAudioPayload.detect(OralPcm.wav(pcm, 16000));
            var state = as(c, () -> oral.completeStreamedAudio(c.examId, reserved.submissionId(),
                    new OralAudioRequest(command.clientSessionId(), command.turnId()), audio, finalText.transcript()));
            send(c, Map.of("type", "RESULT", "state", state));
            log.info("oral-stream exam={} turn={} finishMs={} segments={}", c.examId, command.turnId(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), finalText.finalSegments());
            // Giữ kênh invalidation ngắn trong lúc AI quyết định, không giữ phiên STT hoặc audio RAM.
            if (state.phase() == com.avies.backend.entity.enums.OralExamPhase.DECIDING) {
                synchronized (c) { c.audio.reset(); c.working = false; c.finishedAt = System.nanoTime(); }
            } else close(c);
        } catch (Exception failure) { fail(c, code(failure)); }
    }

    /** Lease/slot/role/revocation recheck không chạy mỗi PCM frame và không giữ DB transaction trong lúc ghi âm. */
    private void check() {
        for (var c : clients.values()) {
            synchronized (c) {
                if (c.closed) continue;
                if (c.finishedAt != 0 && System.nanoTime() - c.finishedAt > TimeUnit.SECONDS.toNanos(30)) { close(c); continue; }
                if (c.auth == null && System.nanoTime() - c.created > TimeUnit.SECONDS.toNanos(5)) { close(c); continue; }
                if (c.start == null && System.nanoTime() - c.created > TimeUnit.SECONDS.toNanos(15)) { close(c); continue; }
                if (c.auth == null || c.working || c.start == null || c.checked) continue;
                if (System.nanoTime() - c.lastCheck < TimeUnit.SECONDS.toNanos(5)) continue;
                c.lastCheck = System.nanoTime(); c.checked = true;
            }
            run(c, () -> {
                try {
                    synchronized (c) { if (c.finishing || c.closed) return; }
                    authenticated(c);
                    decoder.decode(((JwtAuthenticationToken)c.auth).getToken().getTokenValue());
                    as(c, () -> oral.streamingLanguage(c.examId, c.start));
                } catch (Exception failure) {
                    synchronized (c) { if (!c.finishing && !c.closed) fail(c, code(failure)); }
                } finally { synchronized (c) { c.checked = false; } }
            });
        }
    }

    /** Chỉ gửi revision sau commit; không trả prompt, đáp án hoặc rubric trên kênh tiến độ. */
    @org.springframework.transaction.event.TransactionalEventListener
    public void changed(OralProgressChanged event) {
        for (var c : clients.values()) if (c.examId == event.examId()) {
            synchronized (c) { if (c.closed || c.auth == null || c.start == null) continue; }
            run(c, () -> { authenticated(c); send(c, Map.of("type", "PROGRESS", "revision", event.revision())); });
        }
    }

    private void authenticated(Client c) {
        if (c.closed || !(c.auth instanceof JwtAuthenticationToken token) || token.getToken().getExpiresAt() == null
                || !clock.instant().isBefore(token.getToken().getExpiresAt())) throw new AppException(ErrorCode.UNAUTHENTICATED);
    }
    private <T> T as(Client c, Supplier<T> work) {
        authenticated(c); var previous = SecurityContextHolder.getContext(); var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(c.auth);
        try { SecurityContextHolder.setContext(context); return work.get(); }
        finally { SecurityContextHolder.setContext(previous); }
    }
    private void run(Client c, Runnable work) {
        try { workers.execute(() -> { try { work.run(); } catch (Exception failure) { fail(c, code(failure)); } }); }
        catch (RejectedExecutionException busy) { fail(c, 1812); }
    }
    private void send(Client c, Object message) {
        if (c.closed) return;
        try { c.socket.sendMessage(new TextMessage(json.writeValueAsString(message))); }
        catch (Exception disconnected) { close(c); }
    }
    private void fail(Client c, int code) { send(c, Map.of("type", "ERROR", "code", code)); close(c); }
    private static int code(Throwable failure) {
        while (failure instanceof ExecutionException && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof org.springframework.security.oauth2.jwt.JwtException) return 1003;
        return failure instanceof AppException safe ? safe.getErrorCode().getCode() : 1811;
    }
    private static AppException invalid() { return new AppException(ErrorCode.ORAL_AUDIO_INVALID); }
    private static void fields(JsonNode root, Set<String> names) { for (String name : root.propertyNames()) if (!names.contains(name)) throw invalid(); }
    private void close(Client c) {
        StreamingTranscriptionService.Session stream;
        synchronized (c) { if (c.closed) return; c.closed = true; clients.remove(c.socket.getId(), c); stream = c.stream; c.audio.reset(); }
        if (stream != null) stream.close();
        try { c.socket.close(); } catch (Exception ignored) { }
    }
    @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) { var c = clients.get(socket.getId()); if (c != null) close(c); }
    @Override public void handleTransportError(WebSocketSession socket, Throwable failure) { var c = clients.get(socket.getId()); if (c != null) close(c); }
    @PreDestroy public void shutdown() { for (var c : clients.values()) close(c); watchdog.shutdownNow(); workers.shutdownNow(); }
    private static Thread daemon(Runnable task, String name) { var thread = new Thread(task, name); thread.setDaemon(true); return thread; }
    private static final class Client {
        final long examId, created = System.nanoTime(); final WebSocketSession socket; final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        Authentication auth; OralSpeechRequest start; StreamingTranscriptionService.Session stream;
        volatile boolean closed; boolean working, finishing, checked; long lastCheck = System.nanoTime(), finishedAt;
        Client(long examId, WebSocketSession socket) { this.examId = examId; this.socket = socket; }
    }
}
