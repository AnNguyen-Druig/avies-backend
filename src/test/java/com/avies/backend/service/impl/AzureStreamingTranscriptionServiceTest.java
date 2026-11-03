package com.avies.backend.service.impl;

import com.avies.backend.config.AzureSpeechSttProperties;
import com.avies.backend.exception.AppException;
import com.avies.backend.service.StreamingTranscriptionService;
import com.avies.backend.service.support.StreamingRecognitionEngine;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Điều khiển event SDK, không gọi cloud/key thật; kiểm tra EOF/finality và giới hạn F0. */
class AzureStreamingTranscriptionServiceTest {
    AzureSpeechSttProperties settings;
    StreamingRecognitionEngine engine;
    StreamingRecognitionEngine.Channel channel;
    AzureStreamingTranscriptionService service;
    final AtomicReference<StreamingRecognitionEngine.Listener> listener = new AtomicReference<>();
    final List<StreamingTranscriptionService.Update> updates = new CopyOnWriteArrayList<>();
    @BeforeEach void setup() {
        settings = mock(AzureSpeechSttProperties.class); when(settings.ready()).thenReturn(true);
        engine = mock(StreamingRecognitionEngine.class); channel = mock(StreamingRecognitionEngine.Channel.class);
        when(engine.open(anyString(), any())).thenAnswer(call -> { listener.set(call.getArgument(1)); return channel; });
        service = new AzureStreamingTranscriptionService(settings, engine);
    }
    @AfterEach void cleanup() { service.close(); }
    StreamingTranscriptionService.Session open() { return service.open("vi", updates::add); }
    void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo(code));
    }
    void assertFailed(CompletionStage<?> result) {
        assertThatThrownBy(() -> result.toCompletableFuture().get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AppException.class);
    }

    @Test void partialIsReplaceableAndOnlyFinalSegmentsProduceTheCompletedTranscript() throws Exception {
        var session = open(); session.write(new byte[3200]);
        listener.get().partial("Xin"); listener.get().partial("Xin chào"); listener.get().recognized("a", "Xin chào.");
        listener.get().recognized("a", "Xin chào.");
        var result = session.finish(); org.junit.jupiter.api.Assertions.assertTrue(session.finish() == result);
        verify(channel).endInput(); verify(channel, never()).close(); assertThat(result.toCompletableFuture()).isNotDone();
        listener.get().partial("Phần cuối"); listener.get().recognized("b", "Phần cuối đã được chốt."); listener.get().stopped();
        var finalResult = result.toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(finalResult.transcript()).isEqualTo("Xin chào. Phần cuối đã được chốt."); assertThat(finalResult.finalSegments()).isEqualTo(2);
        assertThat(updates).extracting(StreamingTranscriptionService.Update::kind).contains(StreamingTranscriptionService.Kind.PARTIAL, StreamingTranscriptionService.Kind.FINAL);
        assertThat(updates.stream().filter(u -> u.kind() == StreamingTranscriptionService.Kind.FINAL).count()).isEqualTo(2);
        verify(channel).close();
        var second = service.open("en", updates::add); second.close();
        verify(engine).open(eq("vi"), any()); verify(engine).open(eq("en"), any());
    }

    @Test void secondSessionCannotReachSdkUntilFirstNativeSessionIsClosed() throws Exception {
        var session = open(); assertCode(() -> open(), 1812); verify(engine, times(1)).open(anyString(), any());
        session.write(new byte[32]); var result = session.finish();
        assertCode(() -> open(), 1812); listener.get().stopped();
        assertThat(result.toCompletableFuture().get(2, TimeUnit.SECONDS).hasSpeech()).isFalse();
        assertThatCode(() -> open().close()).doesNotThrowAnyException();
    }

    @Test void permitIsHeldWhileNativeCleanupIsStillRunning() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(2, TimeUnit.SECONDS)).isTrue(); return null; }).when(channel).close();
        var session = open(); session.write(new byte[32]); var result = session.finish(); listener.get().stopped();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertCode(this::open, 1812);
            assertThat(result.toCompletableFuture()).isNotDone();
            verify(engine, times(1)).open(anyString(), any());
        } finally { release.countDown(); }
        assertThat(result.toCompletableFuture().get(2, TimeUnit.SECONDS).hasSpeech()).isFalse();
    }

    @Test void audioAndTranscriptSizeLimitsCannotBeBypassedBySmallChunks() {
        var session = open();
        for (int i = 0; i < AzureStreamingTranscriptionService.MAX_PCM_BYTES / 64000; i++) session.write(new byte[64000]);
        assertCode(() -> session.write(new byte[2]), 1813); assertFailed(session.finish());
        var next = open(); next.write(new byte[32]);
        listener.get().recognized("a", "x".repeat(15000)); listener.get().recognized("b", "y".repeat(6000));
        assertFailed(next.finish());
    }

    @Test void stopWithUnfinalizedPartialOrProviderFailureIsNeverSuccessfulEmptyText() {
        var session = open(); session.write(new byte[32]); listener.get().partial("Còn đoạn cuối chưa chốt");
        var result = session.finish(); listener.get().stopped(); assertFailed(result);
        var next = open(); next.write(new byte[32]); listener.get().failed(1812); assertFailed(next.finish());
        assertThat(updates.stream().anyMatch(u -> u.kind() == StreamingTranscriptionService.Kind.FINAL)).isFalse();
    }

    @Test void cancelDisconnectOrUnexpectedRemoteStopDoesNotSubmitPartial() {
        var session = open(); session.write(new byte[32]); listener.get().partial("Chưa nộp");
        session.close(); assertFailed(session.finish());
        session = open(); session.write(new byte[32]); listener.get().stopped(); assertFailed(session.finish());
    }

    @Test void missingConfigOrUnsupportedLanguageCannotReachAzure() {
        when(settings.ready()).thenReturn(false); assertCode(this::open, 1810);
        when(settings.ready()).thenReturn(true); assertCode(() -> service.open("fr", updates::add), 1813);
        assertCode(() -> service.open(null, updates::add), 1813); verifyNoInteractions(engine);
    }

    @Test void rejectsMalformedOrContainerAudioAndEmptySubmission() {
        for (var bytes : List.of(new byte[0], new byte[3], new byte[64002], new byte[]{'R','I','F','F'})) {
            var session = open(); assertCode(() -> session.write(bytes), 1813); assertFailed(session.finish());
        }
        var session = open(); assertFailed(session.finish()); verify(channel, never()).write(any());
    }

    @Test void idleAndFinalizationTimeoutFreeTheGateWithoutInventingTranscript() {
        var session = open(); session.write(new byte[32]);
        service.checkTimeouts(System.nanoTime() + TimeUnit.SECONDS.toNanos(11)); assertFailed(session.finish());
        session = open(); session.write(new byte[32]); var result = session.finish();
        service.checkTimeouts(System.nanoTime() + TimeUnit.SECONDS.toNanos(16)); assertFailed(result);
    }

    @Test void sdkOpenFailureAfterErrorCallbackStillCleansUpAndAllowsRetry() throws Exception {
        when(engine.open(anyString(), any())).thenAnswer(call -> { ((StreamingRecognitionEngine.Listener)call.getArgument(1)).failed(1810); throw new IllegalStateException("not logged"); });
        assertCode(this::open, 1810);
        doReturn(channel).when(engine).open(anyString(), any());
        for (int i=0; i<100; i++) {
            try { open().close(); return; }
            catch (AppException busy) { assertThat(busy.getErrorCode().getCode()).isEqualTo(1812); Thread.sleep(5); }
        }
        fail("Gate không được giải phóng sau lỗi mở SDK");
    }

    @Test void nativeCleanupFailureKeepsTheGateClosedRatherThanStartingASecondAzureRequest() {
        var session = open(); session.write(new byte[32]); var result = session.finish();
        doThrow(new IllegalStateException("not logged")).when(channel).close(); listener.get().stopped(); assertFailed(result);
        assertCode(this::open, 1812); verify(engine, times(1)).open(anyString(), any());
    }

    @Test void callbackFailureAndInvalidProviderTextBecomeTechnicalErrors() {
        var session = service.open("en", update -> { throw new IllegalStateException("socket closed"); }); session.write(new byte[32]);
        listener.get().partial("Hello"); assertFailed(session.finish());
        session = open(); session.write(new byte[32]); listener.get().recognized("id", "bad\0text"); assertFailed(session.finish());
    }
}
