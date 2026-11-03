package com.avies.backend.service.impl;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.controller.OralAudioWebSocket;
import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.OralExamResponse;
import com.avies.backend.entity.enums.*;
import com.avies.backend.service.OralExamService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.socket.config.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual loopback WebSocket → production handler → Azure F0; exam persistence is mocked, no Supabase. */
@Tag("external-services") @EnabledIfSystemProperty(named="allowExternalServices", matches="true")
class OralStreamingSocketLiveTest {
    @Configuration @EnableWebMvc @EnableWebSocket
    static class WebConfig implements WebSocketConfigurer {
        private final OralAudioWebSocket handler;
        WebConfig(OralAudioWebSocket handler) { this.handler = handler; }
        @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(handler, "/student-exams/{id}/oral-examination/stream").setAllowedOrigins("http://localhost:5173");
        }
    }
    @ParameterizedTest @CsvSource({"vi,vi-VN-HoaiMyNeural,sinh viên", "en,en-US-AriaNeural,student"})
    void realSocketStreamsPartialAndFinalBeforeAtomicSubmission(String language, String voice, String expected) throws Exception {
        var settings = AzureSpeechStreamingLiveTest.settings(); assertTrue(settings.ready(), "Azure .env required");
        byte[] pcm = AzureSpeechStreamingLiveTest.fixture(settings, voice, language.equals("vi")
                ? "Xin chào. Sinh viên đang trả lời câu hỏi bằng micro. Đây là kiểm tra nhận dạng giọng nói tiếng Việt."
                : "Hello. The student is answering the question using a microphone. This is a streaming speech recognition test.");
        var oral = mock(OralExamService.class); var decoder = mock(CustomJwtDecoder.class); var now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID clientId = UUID.randomUUID(), turn = UUID.randomUUID(), job = UUID.randomUUID(); var json = new ObjectMapper();
        when(decoder.decode("local-test-token")).thenReturn(Jwt.withTokenValue("local-test-token").header("alg", "HS512").subject("student")
                .issuedAt(now.minusSeconds(1).toInstant()).expiresAt(now.plusMinutes(10).toInstant()).claim("scope", "ROLE_STUDENT").build());
        when(oral.streamingLanguage(eq(7L), any())).thenReturn(language);
        when(oral.execute(eq(7L), any())).thenReturn(new OralExamResponse(7L, StudentExamStatus.IN_PROGRESS, OralExamPhase.TRANSCRIBING,
                3L, now, now.plusMinutes(20), now.plusMinutes(1), 1, 1, turn, 0, language, 0, 1, 90000L, job, List.of(), List.of(), false, null));
        when(oral.completeStreamedAudio(eq(7L), eq(job), any(), any(), any())).thenAnswer(call -> {
            assertTrue(((String)call.getArgument(4)).toLowerCase(Locale.ROOT).contains(expected));
            var payload = (com.avies.backend.service.support.OralAudioPayload)call.getArgument(3);
            assertArrayEquals(com.avies.backend.service.support.OralPcm.wav(pcm, 16000), payload.bytes());
            return new OralExamResponse(7L, StudentExamStatus.IN_PROGRESS, OralExamPhase.DECIDING,
                    4L, now, now.plusMinutes(20), now.plusMinutes(1), 1, 1, turn, 0, language, 0, 1, 90000L, job, List.of(), List.of(), true, null);
        });
        var azure = new AzureStreamingTranscriptionService(settings, new AzureSpeechStreamingEngine(settings));
        var handler = new OralAudioWebSocket(oral, azure, decoder, Validation.buildDefaultValidatorFactory().getValidator(), Clock.systemUTC());
        var context = new AnnotationConfigWebApplicationContext();
        context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("oralAudioWebSocket", handler)); context.register(WebConfig.class);
        var factory = new TomcatServletWebServerFactory(0); factory.setAddress(InetAddress.getLoopbackAddress());
        factory.addContextCustomizers(tomcat -> tomcat.addServletContainerInitializer(new org.apache.tomcat.websocket.server.WsSci(), Set.of()));
        var server = factory.getWebServer(servletContext -> {
            context.setServletContext(servletContext); context.refresh();
            var registration = servletContext.addServlet("dispatcher", new DispatcherServlet(context)); registration.setLoadOnStartup(1); registration.addMapping("/");
        });
        var ready = new CompletableFuture<Void>(); var result = new CompletableFuture<Void>(); var partial = new AtomicInteger(); var finals = new AtomicInteger();
        java.net.http.WebSocket socket = null;
        try {
            server.start();
            var listener = new java.net.http.WebSocket.Listener() {
                final StringBuilder buffer = new StringBuilder();
                @Override public void onOpen(java.net.http.WebSocket ws) { ws.request(1); }
                @Override public CompletionStage<?> onText(java.net.http.WebSocket ws, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) try {
                        var event = json.readTree(buffer.toString()); buffer.setLength(0);
                        switch (event.path("type").asText()) {
                            case "AUTHENTICATED" -> ws.sendText(json.writeValueAsString(Map.of("type","START","request",new OralSpeechRequest(clientId,turn,2L))),true);
                            case "READY" -> ready.complete(null);
                            case "PARTIAL" -> partial.incrementAndGet();
                            case "FINAL" -> finals.incrementAndGet();
                            case "RESULT" -> result.complete(null);
                            case "ERROR" -> { var safe = new IllegalStateException("Controlled stream code " + event.path("code").asInt()); ready.completeExceptionally(safe); result.completeExceptionally(safe); }
                        }
                    } catch (Exception invalid) { result.completeExceptionally(new IllegalStateException("Invalid socket response")); }
                    ws.request(1); return null;
                }
                @Override public void onError(java.net.http.WebSocket ws, Throwable error) { ready.completeExceptionally(new IllegalStateException("WebSocket failed")); result.completeExceptionally(new IllegalStateException("WebSocket failed")); }
            };
            try (var http = HttpClient.newHttpClient()) {
                socket = http.newWebSocketBuilder().header("Origin","http://localhost:5173")
                        .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/student-exams/7/oral-examination/stream"), listener).get(10,TimeUnit.SECONDS);
                socket.sendText("{\"type\":\"AUTH\",\"token\":\"local-test-token\"}",true).get(5,TimeUnit.SECONDS); ready.get(15,TimeUnit.SECONDS);
                for (int offset=0;offset<pcm.length;offset+=3200) {
                    socket.sendBinary(ByteBuffer.wrap(Arrays.copyOfRange(pcm,offset,Math.min(offset+3200,pcm.length))),true).get(2,TimeUnit.SECONDS); Thread.sleep(100);
                }
                long finish = System.nanoTime();
                socket.sendText(json.writeValueAsString(Map.of("type","FINISH","command",new OralExamCommandRequest(UUID.randomUUID(),clientId,2L,turn,OralExamAction.SUBMIT,null))),true).get(5,TimeUnit.SECONDS);
                result.get(25,TimeUnit.SECONDS); assertTrue(partial.get()>0); assertTrue(finals.get()>0);
                verify(oral).completeStreamedAudio(eq(7L),eq(job),any(),any(),any());
                System.out.printf("AZURE_WS_LIVE language=%s partials=%d finals=%d finishToResultMs=%d%n",language,partial.get(),finals.get(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-finish));
            }
        } finally { if(socket!=null) socket.abort(); handler.shutdown(); azure.close(); server.stop(); context.close(); }
    }
}
