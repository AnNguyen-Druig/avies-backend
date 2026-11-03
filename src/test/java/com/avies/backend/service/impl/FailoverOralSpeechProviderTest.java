package com.avies.backend.service.impl;

import com.avies.backend.exception.*;
import com.avies.backend.service.OralSpeechProvider;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FailoverOralSpeechProviderTest {
    GeminiOralSpeechProvider gemini; AzureOralSpeechProvider azure; Clock clock; FailoverOralSpeechProvider provider;
    final Instant start = Instant.parse("2035-01-01T00:00:00Z");
    @BeforeEach void setup() {
        gemini = mock(GeminiOralSpeechProvider.class); azure = mock(AzureOralSpeechProvider.class); clock = mock(Clock.class);
        when(clock.instant()).thenReturn(start); when(azure.ready()).thenReturn(true); provider = new FailoverOralSpeechProvider(gemini,azure,clock);
        doAnswer(call -> { ((Consumer<byte[]>)call.getArgument(2)).accept(new byte[]{2,0}); return null; }).when(azure).stream(anyString(),anyString(),any());
    }
    @Test void geminiRemainsPrimaryAndHealthyRequestsNeverCallAzure() {
        doAnswer(call -> { ((Consumer<byte[]>)call.getArgument(2)).accept(new byte[]{1,0}); return null; }).when(gemini).stream(anyString(),anyString(),any());
        var chunks = new ArrayList<byte[]>(); provider.stream("Question?","en",chunks::add);
        assertThat(chunks.getFirst()).containsExactly((byte)1,(byte)0); verify(azure,never()).stream(any(),any(),any());
    }
    @Test void quotaBeforeFirstChunkFallsBackAndCooldownAvoidsRepeatingFailedRequests() {
        doThrow(new AppException(ErrorCode.ORAL_SPEECH_QUOTA)).when(gemini).stream(anyString(),anyString(),any());
        var chunks = new ArrayList<byte[]>(); provider.stream("Question one?","en",chunks::add); provider.stream("Question two?","en",chunks::add);
        verify(gemini,times(1)).stream(anyString(),anyString(),any()); verify(azure,times(2)).stream(anyString(),anyString(),any());
        assertThat(chunks).hasSize(2); when(clock.instant()).thenReturn(start.plusSeconds(301));
        doNothing().when(gemini).stream(anyString(),anyString(),any()); provider.stream("Question three?","en",chunks::add);
        verify(gemini,times(2)).stream(anyString(),anyString(),any()); verify(azure,times(2)).stream(anyString(),anyString(),any());
    }
    @Test void cannotSwitchVoiceAfterGeminiHasAlreadyEmittedAudio() {
        doAnswer(call -> { ((Consumer<byte[]>)call.getArgument(2)).accept(new byte[]{1,0}); throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); })
                .when(gemini).stream(anyString(),anyString(),any());
        var chunks = new ArrayList<byte[]>(); assertThatThrownBy(() -> provider.stream("Question?","vi",chunks::add)).isInstanceOf(AppException.class);
        assertThat(chunks).hasSize(1); verify(azure,never()).stream(any(),any(),any());
        // User replay can safely use Azure for the whole question during the short cooldown.
        provider.stream("Question?","vi",chunks::add); verify(azure).stream(eq("Question?"),eq("vi"),any());
    }
    @Test void disabledBackupPreservesOriginalErrorAndDoesNotSpendAzureQuota() {
        when(azure.ready()).thenReturn(false); doThrow(new AppException(ErrorCode.ORAL_SPEECH_QUOTA)).when(gemini).stream(anyString(),anyString(),any());
        assertThatThrownBy(() -> provider.stream("Question?","en",ignored -> {})).isInstanceOfSatisfying(AppException.class,e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORAL_SPEECH_QUOTA));
        verify(azure,never()).stream(any(),any(),any());
    }
    @Test void previewAndLegacyWavUseTheSameBackupPolicy() {
        when(gemini.synthesize(anyString(),anyString())).thenThrow(new AppException(ErrorCode.ORAL_SPEECH_QUOTA));
        when(azure.synthesize(anyString(),anyString())).thenReturn(GeminiOralSpeechProviderTest.wav());
        assertThat(provider.synthesize("Preview?","vi")).isEqualTo(GeminiOralSpeechProviderTest.wav()); verify(azure).synthesize("Preview?","vi");
    }
    @Test void springSelectsCompositeRatherThanBypassingBackupThroughGeminiBean() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("gemini",GeminiOralSpeechProvider.class,()->gemini); context.registerBean("azure",AzureOralSpeechProvider.class,()->azure);
            context.registerBean("clock",Clock.class,()->clock); context.register(FailoverOralSpeechProvider.class); context.refresh();
            assertThat(context.getBean(OralSpeechProvider.class)).isInstanceOf(FailoverOralSpeechProvider.class);
        }
    }
}
