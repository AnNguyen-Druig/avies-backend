package com.avies.backend.controller;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.OralExamResponse;
import com.avies.backend.entity.enums.*;
import com.avies.backend.exception.*;
import com.avies.backend.service.*;
import com.avies.backend.service.support.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OralAudioWebSocketTest {
    final OralExamService oral = mock(OralExamService.class);
    final StreamingTranscriptionService stt = mock(StreamingTranscriptionService.class);
    final StreamingTranscriptionService.Session stream = mock(StreamingTranscriptionService.Session.class);
    final CustomJwtDecoder decoder = mock(CustomJwtDecoder.class);
    final WebSocketSession socket = mock(WebSocketSession.class);
    final List<String> messages = new CopyOnWriteArrayList<>();
    final UUID client = UUID.randomUUID(), turn = UUID.randomUUID(), job = UUID.randomUUID();
    final ObjectMapper json = new ObjectMapper();
    final OffsetDateTime now = OffsetDateTime.parse("2035-01-01T10:00:00Z");
    OralAudioWebSocket handler;
    @BeforeEach void setup() throws Exception {
        when(socket.getId()).thenReturn("fixture"); when(socket.getUri()).thenReturn(URI.create("ws://localhost/student-exams/7/oral-examination/stream")); when(socket.isOpen()).thenReturn(true);
        doAnswer(call -> { messages.add(((TextMessage)call.getArgument(0)).getPayload()); return null; }).when(socket).sendMessage(any());
        var token = Jwt.withTokenValue("fixture-token").header("alg", "HS512").subject("student").issuedAt(now.minusSeconds(1).toInstant())
                .expiresAt(now.plusHours(1).toInstant()).claim("scope", "ROLE_STUDENT").build(); when(decoder.decode("fixture-token")).thenReturn(token);
        when(oral.streamingLanguage(eq(7L), any())).thenAnswer(call -> { assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("student"); return "vi"; });
        when(stt.open(eq("vi"), any())).thenReturn(stream);
        handler = new OralAudioWebSocket(oral, stt, decoder, Validation.buildDefaultValidatorFactory().getValidator(), Clock.fixed(now.toInstant(), ZoneOffset.UTC));
        handler.afterConnectionEstablished(socket);
    }
    @AfterEach void cleanup() { handler.shutdown(); SecurityContextHolder.clearContext(); }
    void text(Object value) throws Exception { handler.handleMessage(socket, new TextMessage(json.writeValueAsString(value))); }
    void start() throws Exception {
        text(Map.of("type", "AUTH", "token", "fixture-token"));
        text(Map.of("type", "START", "request", new OralSpeechRequest(client, turn, 2L)));
        await("READY");
    }
    void await(String type) throws Exception {
        for (int i = 0; i < 300; i++) { if (messages.stream().anyMatch(s -> s.contains("\"type\":\"" + type + "\""))) return; Thread.sleep(5); }
        fail("Missing event " + type);
    }
    OralExamResponse state(OralExamPhase phase) {
        return state(phase,3L);
    }
    OralExamResponse state(OralExamPhase phase,long revision) {
        return new OralExamResponse(7L, StudentExamStatus.IN_PROGRESS, phase, revision, now, now.plusMinutes(20), now.plusSeconds(45), 1, 1, turn, 0, "vi", 0, 1, 90000L, job, List.of(), List.of(), false, null);
    }
    @Test void requiresAuthenticationOwnershipAndStartBeforeAnyAudioReachesAzure() throws Exception {
        handler.handleMessage(socket, new BinaryMessage(new byte[32])); await("ERROR"); verifyNoInteractions(stt, oral);
    }
    @Test void ownerFailureNeverStartsRecognition() throws Exception {
        when(oral.streamingLanguage(anyLong(), any())).thenThrow(new AppException(ErrorCode.ORAL_ACTIVE_CLIENT));
        text(Map.of("type", "AUTH", "token", "fixture-token")); text(Map.of("type", "START", "request", new OralSpeechRequest(client, turn, 2L)));
        await("ERROR"); verifyNoInteractions(stt);
    }
    @Test void streamsTrustedTranscriptsAndPersistsExactAudioOnlyAfterSubmit() throws Exception {
        when(stt.open(eq("vi"), any())).thenAnswer(call -> { Consumer<StreamingTranscriptionService.Update> updates = call.getArgument(1);
            updates.accept(new StreamingTranscriptionService.Update(StreamingTranscriptionService.Kind.PARTIAL, 1, "Xin"));
            updates.accept(new StreamingTranscriptionService.Update(StreamingTranscriptionService.Kind.FINAL, 1, "Xin chào.")); return stream; });
        when(stream.finish()).thenReturn(CompletableFuture.completedFuture(new StreamingTranscriptionService.Result("Xin chào.", 1)));
        when(oral.execute(eq(7L), any())).thenReturn(state(OralExamPhase.TRANSCRIBING));
        when(oral.completeStreamedAudio(eq(7L), eq(job), any(), any(), eq("Xin chào."))).thenAnswer(call -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("student");
            OralAudioPayload audio = call.getArgument(3); assertThat(audio.bytes()).isEqualTo(OralPcm.wav(new byte[3200], 16000));
            return state(OralExamPhase.DECIDING);
        });
        start(); await("PARTIAL"); await("FINAL"); handler.handleMessage(socket, new BinaryMessage(new byte[3200]));
        verify(oral, never()).execute(anyLong(), any()); verify(stream).write(new byte[3200]);
        text(Map.of("type", "FINISH", "command", new OralExamCommandRequest(UUID.randomUUID(), client, 2L, turn, OralExamAction.SUBMIT, null)));
        await("RESULT"); verify(oral).completeStreamedAudio(eq(7L), eq(job), any(), any(), eq("Xin chào."));
        assertThat(messages.stream().filter(s -> s.contains("\"type\":\"RESULT\"")).findFirst().orElseThrow()).contains("\"serverTime\":\"2035-01-01T10:00:00Z\"");
        when(oral.get(7L)).thenReturn(state(OralExamPhase.PREPARING,8L));
        handler.changed(new OralProgressChanged(7L, 8L)); await("PROGRESS");
        assertThat(messages.stream().filter(s -> s.contains("\"type\":\"PROGRESS\"")).findFirst().orElseThrow())
                .contains("\"revision\":8","\"state\":","\"phase\":\"PREPARING\"").doesNotContain("Xin chào", "prompt", "rubric");
        verify(decoder,atLeast(2)).decode("fixture-token"); verify(oral).get(7L);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @Test void rejectsClientTranscriptAndDisconnectDoesNotSubmitAnAnswer() throws Exception {
        start(); text(Map.of("type", "FINISH", "command", new OralExamCommandRequest(UUID.randomUUID(), client, 2L, turn, OralExamAction.SUBMIT, null), "transcript", "FAKE"));
        await("ERROR"); verify(oral, never()).execute(anyLong(), any()); verify(stream).close();
    }
    @Test void disconnectBeforeFinishCancelsRecognitionWithoutSavingPartial() throws Exception {
        start(); handler.afterConnectionClosed(socket, CloseStatus.NORMAL); verify(stream).close(); verify(oral, never()).completeStreamedAudio(any(), any(), any(), any(), any());
    }
    @Test void rejectsTokenInUrlBeforeAuthentication() throws Exception {
        handler.afterConnectionClosed(socket, CloseStatus.NORMAL); when(socket.getUri()).thenReturn(URI.create("ws://localhost/student-exams/7/oral-examination/stream?token=bad"));
        handler.afterConnectionEstablished(socket); verify(socket).close(CloseStatus.POLICY_VIOLATION); verifyNoInteractions(stt, decoder);
    }
    @Test void progressBeforeSubmitIsRevisionOnlyAndDoesNotReadOrDiscloseTheConversation() throws Exception {
        start(); handler.changed(new OralProgressChanged(7L,4)); await("PROGRESS");
        assertThat(messages.stream().filter(s->s.contains("\"type\":\"PROGRESS\"")).findFirst().orElseThrow()).doesNotContain("state","transcripts");
        verify(oral,never()).get(anyLong());
    }
    @Test void revokedJwtAfterSubmitCannotReceivePushedStudentState() throws Exception {
        start(); when(stream.finish()).thenReturn(CompletableFuture.completedFuture(new StreamingTranscriptionService.Result("Final.",1)));
        when(oral.execute(eq(7L),any())).thenReturn(state(OralExamPhase.TRANSCRIBING));
        when(oral.completeStreamedAudio(any(),any(),any(),any(),any())).thenReturn(state(OralExamPhase.DECIDING));
        handler.handleMessage(socket,new BinaryMessage(new byte[3200]));
        text(Map.of("type","FINISH","command",new OralExamCommandRequest(UUID.randomUUID(),client,2L,turn,OralExamAction.SUBMIT,null))); await("RESULT");
        when(decoder.decode("fixture-token")).thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("revoked fixture"));
        handler.changed(new OralProgressChanged(7L,8)); await("ERROR");
        verify(oral,never()).get(anyLong());
        assertThat(messages).noneMatch(s->s.contains("\"type\":\"PROGRESS\""));
    }
}
