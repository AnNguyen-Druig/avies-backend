package com.avies.backend.exam;

import com.avies.backend.service.support.*;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** Không gửi mạng: đo cô lập lượt thi, revision, hết hạn và thread transaction. */
class OralProgressEventsTest {
    final Instant start = Instant.parse("2035-01-01T00:00:00Z");
    final Clock clock = mock(Clock.class);
    final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    final List<Runnable> tasks = new ArrayList<>();
    final List<SseEmitter> emitters = new ArrayList<>();
    final OralProgressEvents hub = new OralProgressEvents(executor, clock) {
        @Override protected SseEmitter newEmitter(long timeout) { var emitter = mock(SseEmitter.class); emitters.add(emitter); return emitter; }
    };
    OralProgressEventsTest() {
        when(clock.instant()).thenReturn(start);
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
    }
    void drain() { var queued = List.copyOf(tasks); tasks.clear(); queued.forEach(Runnable::run); }

    @Test void sendsOnlyToMatchingAttemptAndDropsDuplicateOrOlderRevision() throws Exception {
        hub.subscribe(1, 2, start.plusSeconds(3600)); hub.subscribe(2, 3, null);
        clearInvocations(emitters.toArray());
        hub.changed(new OralProgressChanged(1, 4)); verifyNoInteractions(emitters.toArray());
        drain(); verify(emitters.getFirst()).send(any(SseEmitter.SseEventBuilder.class)); verifyNoInteractions(emitters.get(1));
        clearInvocations(emitters.toArray());
        hub.changed(new OralProgressChanged(1, 4)); hub.changed(new OralProgressChanged(1, 2)); drain();
        verifyNoInteractions(emitters.toArray());
    }

    @Test void tokenExpiryAndConnectionLifetimeStopSendingAndDoNotRenewExamLease() throws Exception {
        hub.subscribe(1, 2, start.plusSeconds(3)); hub.subscribe(2, 2, null);
        clearInvocations(emitters.toArray()); when(clock.instant()).thenReturn(start.plusSeconds(3));
        hub.heartbeat(); drain(); verify(emitters.getFirst()).complete(); verify(emitters.get(1)).send(any(SseEmitter.SseEventBuilder.class));
        when(clock.instant()).thenReturn(start.plusSeconds(60)); hub.heartbeat(); drain(); verify(emitters.get(1)).complete();
        hub.changed(new OralProgressChanged(2, 3)); assertThat(tasks).isEmpty();
    }

    @Test void clientErrorsOrFullExecutorDoNotEscapeIntoCommittedExamOperation() throws Exception {
        hub.subscribe(1, 0, null); doThrow(new java.io.IOException("Disconnected")).when(emitters.getFirst()).send(any(SseEmitter.SseEventBuilder.class));
        hub.changed(new OralProgressChanged(1, 1)); assertThatCode(this::drain).doesNotThrowAnyException(); verify(emitters.getFirst()).complete();
        hub.subscribe(2, 0, null); doThrow(new java.util.concurrent.RejectedExecutionException()).when(executor).execute(any(Runnable.class));
        assertThatCode(() -> hub.changed(new OralProgressChanged(2, 1))).doesNotThrowAnyException(); verify(emitters.get(1)).complete();
    }

    @Test void boundsConnectionsAndRejectsExpiredTokensBeforeAllocatingEmitter() {
        assertThatThrownBy(() -> hub.subscribe(1, 0, start)).isInstanceOf(ResponseStatusException.class);
        for (int i = 0; i < 4; i++) hub.subscribe(1, 0, null);
        assertThatThrownBy(() -> hub.subscribe(1, 0, null)).isInstanceOf(ResponseStatusException.class);
        assertThat(emitters).hasSize(4);
    }
}
