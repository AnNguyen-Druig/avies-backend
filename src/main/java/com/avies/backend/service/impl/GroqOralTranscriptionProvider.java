package com.avies.backend.service.impl;

import com.avies.backend.config.OralTranscriptionProperties;
import com.avies.backend.exception.*;
import com.avies.backend.service.OralTranscriptionProvider;
import com.avies.backend.service.support.OralAudioPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static com.avies.backend.exception.OralTranscriptionException.Reason.*;

/** Groq Whisper multilingual, dùng transcription để giữ nguyên ngôn ngữ, không dịch sang Anh. */
@Service
@lombok.extern.slf4j.Slf4j
public class GroqOralTranscriptionProvider implements OralTranscriptionProvider {
    /** Key/model ở BE. */ private final OralTranscriptionProperties settings;
    /** HTTP có timeout, không tự retry quota/key lỗi. */ private final RestClient client;
    /** Parser cùng phiên bản Spring Boot. */ private final ObjectMapper json = new ObjectMapper();
    /** Chỉ gọi host Groq cố định. */
    @Autowired public GroqOralTranscriptionProvider(OralTranscriptionProperties settings) {
        this.settings = settings;
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(5000); factory.setReadTimeout(35000);
        client = RestClient.builder().requestFactory(factory).baseUrl("https://api.groq.com").build();
    }
    /** HTTP stub cho kiểm thử, không thay URL production qua request. */
    GroqOralTranscriptionProvider(OralTranscriptionProperties settings, RestClient client) { this.settings = settings; this.client = client; }
    /** Chỉ gửi audio; không gửi reference_answer hoặc prompt dẫn dắt nội dung Student. */
    @Override public String transcribe(OralAudioPayload audio, String language) {
        if (!settings.ready()) throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_NOT_READY);
        if (!Set.of("vi", "en").contains(language) || !Set.of("whisper-large-v3-turbo", "whisper-large-v3").contains(settings.getModel()))
            throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_NOT_READY);
        var form = new LinkedMultiValueMap<String,Object>();
        var headers = new HttpHeaders(); headers.setContentType(MediaType.parseMediaType(audio.mediaType()));
        form.add("file", new HttpEntity<>(new ByteArrayResource(audio.bytes()) {
            @Override public String getFilename() { return "answer." + audio.extension(); }
        }, headers));
        form.add("model", settings.getModel()); form.add("language", language); form.add("response_format", "verbose_json"); form.add("temperature", "0");
        form.add("timestamp_granularities[]", "segment");
        long started=System.nanoTime();
        try { return request(form); }
        catch (OralTranscriptionException failure) {
            // Chỉ retry lỗi 5xx đến sớm để chừa thời gian cho claim 60 giây và callback.
            if (!failure.transientServerError() || TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)>=5000) throw failure;
            log.warn("oral-stt retry=1 reason={} httpStatus={}",failure.getReason(),failure.getProviderStatus());
            try { Thread.sleep(200); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw failure; }
            return request(form);
        }
    }
    /** Một lần gọi ngoài transaction; không log body lỗi có thể chứa thông tin nhạy cảm. */
    private String request(LinkedMultiValueMap<String,Object> form) {
        try {
            return client.post().uri("/openai/v1/audio/transcriptions").header("Authorization", "Bearer " + settings.getApiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(form).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new OralTranscriptionException(ErrorCode.ORAL_TRANSCRIPTION_NOT_READY,HTTP_ERROR,status,null);
                        if (status == 429) throw new OralTranscriptionException(ErrorCode.ORAL_TRANSCRIPTION_QUOTA,HTTP_ERROR,status,null);
                        if (status == 400 || status == 413 || status == 422) throw new OralTranscriptionException(ErrorCode.ORAL_AUDIO_INVALID,HTTP_ERROR,status,null);
                        if (status != 200) throw new OralTranscriptionException(ErrorCode.ORAL_TRANSCRIPTION_FAILED,HTTP_ERROR,status,null);
                        byte[] bytes = response.getBody().readNBytes(1024 * 1024 + 1);
                        if (bytes.length > 1024 * 1024) throw invalid(RESPONSE_TOO_LARGE,null);
                        JsonNode root;
                        try { root=json.readTree(bytes); } catch (RuntimeException malformed) { throw invalid(INVALID_JSON,null); }
                        return decode(root);
                    });
        } catch (AppException safe) { throw safe; }
        catch (Exception failure) {
            Throwable cause=failure; while (cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
            throw invalid(cause instanceof java.net.SocketTimeoutException ? TIMEOUT : NETWORK_ERROR,null);
        }
    }
    /** Không coi hallucination ở đoạn im lặng hoặc response thiếu metadata là câu trả lời thật. */
    static String decode(JsonNode root) {
        try {
            if (root==null || !root.path("text").isString() || !root.path("duration").isNumber() || !root.path("segments").isArray()) throw invalid(INVALID_METADATA,null);
            double duration = root.path("duration").asDouble();
            if (!Double.isFinite(duration) || duration < 0.01 || duration > 305 || root.path("segments").size() > 1000) throw invalid(INVALID_METADATA,null);
            var text = new StringBuilder(); double previous = 0; int index=0;
            for (var segment : root.path("segments")) {
                int current=index++;
                if (!segment.path("text").isString()) throw invalid(INVALID_METADATA,current);
                double start = number(segment, "start",current), end = number(segment, "end",current), silence = number(segment, "no_speech_prob",current), confidence = number(segment, "avg_logprob",current);
                if (start < previous || end < start || end > duration + 1) throw invalid(INVALID_TIMESTAMPS,current);
                if (silence < 0 || silence > 1) throw invalid(INVALID_METADATA,current);
                previous = start;
                if (silence > 0.6) continue;
                if (confidence < -1.0) throw invalid(LOW_CONFIDENCE,current);
                String part = segment.path("text").asText().strip();
                if (!part.isEmpty()) { if (!text.isEmpty()) text.append(' '); text.append(part); }
                if (text.length() > 20000 || text.indexOf("\0") >= 0) throw invalid(INVALID_TEXT,current);
            }
            if (root.path("segments").isEmpty() && !root.path("text").asText().isBlank()) throw invalid(INVALID_METADATA,null);
            return text.toString();
        } catch (OralTranscriptionException safe) { throw safe; }
        catch (Exception malformed) { throw invalid(INVALID_METADATA,null); }
    }
    /** Không dùng giá trị mặc định 0 khi metadata bị thiếu hoặc sai kiểu. */
    private static double number(JsonNode node, String field, int index) {
        if (!node.path(field).isNumber() || !Double.isFinite(node.path(field).asDouble())) throw invalid(INVALID_METADATA,index);
        return node.path(field).asDouble();
    }
    /** Chỉ metadata cố định đi vào log, còn thông báo HTTP dùng mã lỗi cũ. */
    private static OralTranscriptionException invalid(OralTranscriptionException.Reason reason, Integer index) {
        return new OralTranscriptionException(ErrorCode.ORAL_TRANSCRIPTION_FAILED,reason,null,index);
    }
}
