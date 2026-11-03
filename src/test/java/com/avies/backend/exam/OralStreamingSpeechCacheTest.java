package com.avies.backend.exam;

import com.avies.backend.config.OralSpeechProperties;
import com.avies.backend.dto.request.OralSpeechRequest;
import com.avies.backend.service.*;
import com.avies.backend.service.impl.OralSpeechServiceImpl;
import com.avies.backend.service.support.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OralStreamingSpeechCacheTest {
    @Test void joinsWarmupStreamImmediatelyAndReplaysCacheWithoutAnotherProviderCall() throws Exception {
        var oral = mock(OralExamService.class); var provider = mock(OralSpeechProvider.class); var settings = mock(OralSpeechProperties.class);
        when(settings.ready()).thenReturn(true); when(settings.isStreamingEnabled()).thenReturn(true);
        var plan = new OralSpeechPlan(UUID.randomUUID(), "en", "Question number 1. Explain?"); var request = new OralSpeechRequest(UUID.randomUUID(), plan.turnId(), 0L);
        when(oral.speechPlan(1L, request)).thenReturn(plan);
        var first = new CountDownLatch(1); var finish = new CountDownLatch(1); var joined = new CountDownLatch(1);
        doAnswer(call -> { Consumer<byte[]> sink = call.getArgument(2); sink.accept(new byte[]{1, 0}); first.countDown();
            assertThat(finish.await(3, TimeUnit.SECONDS)).isTrue(); sink.accept(new byte[]{2, 0}); return null; }).when(provider).stream(anyString(), anyString(), any());
        var service = new OralSpeechServiceImpl(oral, provider, mock(ExamAccessPolicy.class), settings, Clock.systemUTC());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var warming = executor.submit(() -> service.prefetch(plan)); assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();
            var bytes = new CopyOnWriteArrayList<byte[]>();
            var playing = executor.submit(() -> service.currentStream(1L, request, pcm -> { bytes.add(pcm); joined.countDown(); }));
            try { assertThat(joined.await(2, TimeUnit.SECONDS)).isTrue(); assertThat(playing.isDone()).isFalse(); verify(oral, never()).confirmSpeech(any(), any(), any()); }
            finally { finish.countDown(); }
            warming.get(2, TimeUnit.SECONDS); playing.get(2, TimeUnit.SECONDS);
            assertThat(bytes).hasSize(2); assertThat(bytes.get(1)).containsExactly((byte)2, (byte)0);
            var replay = new ArrayList<byte[]>(); service.currentStream(1L, request, replay::add);
            assertThat(replay.getFirst()).containsExactly((byte)1, (byte)0, (byte)2, (byte)0);
            verify(provider, times(1)).stream(anyString(), anyString(), any()); verify(provider, never()).synthesize(anyString(), anyString());
            verify(oral, times(2)).confirmSpeech(1L, request, "en");
        }
    }
}
