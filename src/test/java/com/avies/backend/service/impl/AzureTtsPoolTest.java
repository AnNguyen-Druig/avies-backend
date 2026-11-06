package com.avies.backend.service.impl;

import com.avies.backend.config.AzureSpeechTtsProperties;
import com.avies.backend.exception.*;
import com.avies.backend.service.support.StreamingSynthesisEngine;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AzureTtsPoolTest {
    final AzureSpeechTtsProperties settings=mock(AzureSpeechTtsProperties.class);
    final StreamingSynthesisEngine engine=mock(StreamingSynthesisEngine.class);
    final List<Fake> opened=new CopyOnWriteArrayList<>();
    AzureOralSpeechProvider provider;
    @BeforeEach void setup() {
        when(settings.ready()).thenReturn(true); when(settings.getApiKey()).thenReturn("fixture-key"); when(settings.getRegion()).thenReturn("southeastasia");
        when(settings.getVietnameseVoice()).thenReturn("en-US-AvaMultilingualNeural"); when(settings.getEnglishVoice()).thenReturn("en-US-AvaMultilingualNeural");
        when(engine.open(anyString(),anyString(),anyString())).thenAnswer(call->{ var session=new Fake(); opened.add(session); return session; });
        provider=new AzureOralSpeechProvider(settings,engine);
    }
    @AfterEach void close() { provider.close(); }
    @Test void reusesTheSameConnectionAcrossVietnameseAndEnglishWithoutReusingAudioOrCallbacks() {
        var first=new ArrayList<byte[]>(); provider.stream("Bạn giải thích use case?","vi",first::add);
        var second=new ArrayList<byte[]>(); provider.stream("Explain use cases?","en",second::add);
        assertThat(opened).hasSize(1); assertThat(opened.getFirst().requests).hasSize(2);
        assertThat(opened.getFirst().requests.getFirst()).contains("vi-VN","en-US","use case");
        assertThat(opened.getFirst().requests.get(1)).doesNotContain("Bạn giải thích");
        assertThat(first).hasSize(1); assertThat(second).hasSize(1); assertThat(first.getFirst()).isNotEqualTo(second.getFirst());
        assertThat(opened.getFirst().closed).isFalse();
    }
    @Test void failedSinkDiscardsItsSessionAndDoesNotRetryOrLeakItToTheNextRequest() {
        assertThatThrownBy(()->provider.stream("Question?","en",pcm->{throw new IllegalStateException("fixture cancellation");})).isInstanceOf(AppException.class);
        assertThat(opened.getFirst().closed).isTrue();
        provider.stream("Another question?","en",ignored->{}); assertThat(opened).hasSize(2);
    }
    @Test void aChangedVoiceOrKeyCannotBorrowAnOlderConfiguration() {
        provider.stream("Question?","en",ignored->{});
        when(settings.getEnglishVoice()).thenReturn("en-US-EmmaMultilingualNeural"); provider.stream("Question?","en",ignored->{});
        when(settings.getApiKey()).thenReturn("rotated-fixture-key"); provider.stream("Question?","en",ignored->{});
        assertThat(opened).hasSize(3); assertThat(opened.get(0).closed).isTrue(); assertThat(opened.get(1).closed).isTrue();
    }
    @Test void failedNativeDisposalCannotCauseUnboundedReplacementConnections() {
        doAnswer(call->{var session=new Fake(){@Override public void close(){throw new IllegalStateException("fixture native cleanup failure");}}; opened.add(session);return session;})
                .when(engine).open(anyString(),anyString(),anyString());
        for(int i=0;i<2;i++) assertThatThrownBy(()->provider.stream("Question?","en",pcm->{throw new IllegalStateException("fixture cancellation");})).isInstanceOf(AppException.class);
        assertThatThrownBy(()->provider.stream("Question?","en",ignored->{})).isInstanceOfSatisfying(AppException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORAL_SPEECH_QUOTA));
        assertThat(opened).hasSize(2);
    }
    @Test void poolNeverStartsMoreThanTwoConcurrentSynthesesAndShutdownClosesActiveBorrowers() throws Exception {
        var entered=new CountDownLatch(2); var release=new CountDownLatch(1);
        doAnswer(call->{var session=new Fake(){@Override public void stream(String text,Consumer<byte[]> sink) {
            entered.countDown(); try { if(!release.await(3,TimeUnit.SECONDS)) throw new AssertionError("release"); } catch(InterruptedException e){throw new AssertionError(e);} super.stream(text,sink);
        }}; opened.add(session);return session;}).when(engine).open(anyString(),anyString(),anyString());
        try(var tasks=Executors.newFixedThreadPool(2)) {
            var first=tasks.submit(()->provider.stream("Question?","en",ignored->{})); var second=tasks.submit(()->provider.stream("Question?","en",ignored->{}));
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->provider.stream("Third?","en",ignored->{})).isInstanceOfSatisfying(AppException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORAL_SPEECH_QUOTA));
                assertThat(opened).hasSize(2); provider.close();
            } finally { release.countDown(); }
            first.get(2,TimeUnit.SECONDS); second.get(2,TimeUnit.SECONDS);
        }
        assertThat(opened).allMatch(s->s.closed);
        assertThatThrownBy(()->provider.stream("Question?","en",ignored->{})).isInstanceOf(AppException.class);
    }
    static class Fake implements StreamingSynthesisEngine.Session {
        final List<String> requests=new ArrayList<>(); final AtomicInteger count=new AtomicInteger(); boolean closed;
        public void preconnect() { assertThat(closed).isFalse(); }
        public void stream(String text,Consumer<byte[]> sink) { assertThat(closed).isFalse(); requests.add(text); sink.accept(new byte[]{(byte)count.incrementAndGet(),0}); }
        public void close() { closed=true; }
    }
}
