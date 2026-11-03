package com.avies.backend.service.support;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/** Invalidation theo lượt thi; HTTP GET có JWT vẫn là nguồn trạng thái và kiểm tra quyền chính thức. */
@Component
public class OralProgressEvents {
    private final ThreadPoolTaskExecutor executor;
    private final Clock clock;
    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();

    public OralProgressEvents(@Qualifier("oralEventsExecutor") ThreadPoolTaskExecutor executor, Clock clock) {
        this.executor = executor; this.clock = clock;
    }

    /** Controller phải xác minh owner trước; kết nối tối đa 60s rồi xác thực JWT lại khi nối tiếp. */
    public synchronized SseEmitter subscribe(long examId, long revision, Instant tokenExpiresAt) {
        if (clients.size() >= 512 || clients.values().stream().filter(c -> c.examId == examId).count() >= 4)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        Instant end = clock.instant().plusSeconds(60);
        if (tokenExpiresAt != null && tokenExpiresAt.isBefore(end)) end = tokenExpiresAt;
        if (!end.isAfter(clock.instant())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var emitter = newEmitter(Math.min(60000L, java.time.Duration.between(clock.instant(), end).toMillis()));
        var client = new Client(examId, revision, end, emitter); clients.put(client.id, client);
        emitter.onCompletion(() -> clients.remove(client.id, client));
        emitter.onTimeout(() -> close(client)); emitter.onError(failure -> clients.remove(client.id, client));
        // Ready được buffer trước khi MVC gắn response; FE GET sau ready để khép khoảng trống đăng ký.
        send(client, "ready", revision);
        return emitter;
    }

    protected SseEmitter newEmitter(long timeout) { return new SseEmitter(Math.max(1L, timeout)); }

    /** Listener chỉ xếp tác vụ nhỏ sau commit, không gửi mạng trên thread đang chốt câu trả lời. */
    @TransactionalEventListener
    public void changed(OralProgressChanged event) {
        for (var client : clients.values()) if (client.examId == event.examId()) dispatch(client, () -> send(client, "progress", event.revision()));
    }

    /** Keepalive không query DB hoặc gia hạn lease; hết hạn buộc mở request JWT mới. */
    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        for (var client : clients.values()) dispatch(client, () -> send(client, null, 0));
    }

    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void shutdown() { for (var client : clients.values()) close(client); }

    private void dispatch(Client client, Runnable task) {
        try { executor.execute(task); }
        catch (RejectedExecutionException full) { close(client); } // FE quay về polling, transaction đã commit không bị báo lỗi.
    }

    private void send(Client client, String name, long revision) {
        synchronized (client) {
            if (!clients.containsKey(client.id)) return;
            if (!clock.instant().isBefore(client.end)) { close(client); return; }
            if ("progress".equals(name) && revision <= client.revision) return;
            revision = Math.max(revision, client.revision); // Ready không làm lùi revision nếu commit đến đúng lúc đăng ký.
            try {
                if (name == null) client.emitter.send(SseEmitter.event().comment("keepalive"));
                else {
                    client.emitter.send(SseEmitter.event().name(name).id(Long.toString(revision))
                            .data(Map.of("revision", revision), MediaType.APPLICATION_JSON));
                    client.revision = revision;
                }
            } catch (Exception disconnected) { close(client); }
        }
    }

    private void close(Client client) {
        if (clients.remove(client.id, client)) client.emitter.complete();
    }

    private static final class Client {
        final UUID id = UUID.randomUUID(); final long examId; final Instant end; final SseEmitter emitter;
        long revision;
        Client(long examId, long revision, Instant end, SseEmitter emitter) {
            this.examId = examId; this.revision = revision; this.end = end; this.emitter = emitter;
        }
    }
}
