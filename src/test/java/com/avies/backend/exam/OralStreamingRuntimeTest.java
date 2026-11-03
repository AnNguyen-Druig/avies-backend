package com.avies.backend.exam;

import com.avies.backend.config.*;
import com.avies.backend.repository.*;
import com.avies.backend.service.impl.OralExamRuntimeServiceImpl;
import com.avies.backend.service.support.ExamAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OralStreamingRuntimeTest {
    @Test void azureCanRunWithoutGroqButDoesNotAdvertiseGroqMicPreview() {
        var speech = mock(OralSpeechProperties.class); var groq = mock(OralTranscriptionProperties.class); var azure = mock(AzureSpeechSttProperties.class);
        var audio = mock(OralAudioRepository.class); var decision = mock(OralDecisionProperties.class); var claims = mock(OralDecisionRepository.class);
        when(speech.ready()).thenReturn(true); when(speech.isStreamingEnabled()).thenReturn(true); when(azure.ready()).thenReturn(true);
        when(audio.ready()).thenReturn(true); when(decision.ready()).thenReturn(true); when(claims.ready()).thenReturn(true);
        var service = new OralExamRuntimeServiceImpl(mock(ExamAccessPolicy.class),speech,groq,audio,decision,claims);
        ReflectionTestUtils.setField(service,"azure",azure);
        var state = service.get(); assertThat(state.providersReady()).isTrue(); assertThat(state.mode()).isEqualTo("LIVE");
        assertThat(state.streamingSttReady()).isTrue(); assertThat(state.streamingTtsReady()).isTrue(); assertThat(state.transcriptionReady()).isFalse();
        when(azure.ready()).thenReturn(false); assertThat(service.get().providersReady()).isFalse();
    }
}
