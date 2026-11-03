package com.avies.backend.service.impl;

import com.avies.backend.config.OralSpeechProperties;
import com.avies.backend.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiSpeechStreamingTest {
    private static String event(String type, String data) { return "event: " + type + "\r\ndata: " + data + "\r\n\r\n"; }
    private static final String AUDIO = event("step.delta", "{\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"mime_type\":\"audio/l16\",\"data\":\"AQACAA==\"}}");
    private static final String END = event("interaction.completed", "{\"event_type\":\"interaction.completed\",\"interaction\":{\"status\":\"completed\"}}");
    @Test void forwardsPcmBeforeReadingCompletionAndRequiresExplicitEnd() throws Exception {
        var received = new ArrayList<byte[]>();
        var first = new ByteArrayInputStream(AUDIO.getBytes(StandardCharsets.UTF_8));
        var last = new ByteArrayInputStream(END.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                assertThat(received).hasSize(1); return super.read(b, off, len);
            }
        };
        GeminiOralSpeechProvider.readStream(new SequenceInputStream(first, last), received::add);
        assertThat(received.getFirst()).containsExactly((byte)1, (byte)0, (byte)2, (byte)0);
        for (var bad : List.of(AUDIO, END, AUDIO + event("error", "{\"event_type\":\"error\",\"error\":{\"message\":\"secret\"}}"),
                AUDIO.replace("AQACAA==", "AQ==") + END, AUDIO.replace("audio/l16", "audio/wav") + END, AUDIO + END + AUDIO)) {
            assertThatThrownBy(() -> GeminiOralSpeechProvider.readStream(new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8)), ignored -> {}))
                    .isInstanceOf(AppException.class).hasMessageNotContaining("secret");
        }
    }
    @Test void sendsStreamingContractAndDoesNotRetryQuota() {
        var settings = mock(OralSpeechProperties.class); when(settings.ready()).thenReturn(true); when(settings.getApiKey()).thenReturn("fixture-key");
        when(settings.getModel()).thenReturn("gemini-3.8-flash-tts"); when(settings.getVoice()).thenReturn("Kore");
        var builder = RestClient.builder().baseUrl("https://generativelanguage.googleapis.com"); var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new GeminiOralSpeechProvider(settings, builder.build()); var chunks = new ArrayList<byte[]>();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/interactions"))
                .andExpect(header("x-goog-api-key", "fixture-key")).andExpect(jsonPath("$.stream").value(true))
                .andExpect(jsonPath("$.store").value(false)).andExpect(jsonPath("$.response_format.mime_type").value("audio/l16"))
                .andRespond(withSuccess(AUDIO + END, MediaType.TEXT_EVENT_STREAM));
        provider.stream("Question number 1. Explain the design.", "en", chunks::add); assertThat(chunks).hasSize(1); server.verify();
        server.reset(); server.expect(anything()).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> provider.stream("Question?", "en", chunks::add)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORAL_SPEECH_QUOTA)); server.verify();
    }
}
