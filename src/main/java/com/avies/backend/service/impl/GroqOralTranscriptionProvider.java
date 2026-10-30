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

/** Groq Whisper multilingual, dùng transcription để giữ nguyên ngôn ngữ, không dịch sang Anh. */
@Service
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
        try {
            return client.post().uri("/openai/v1/audio/transcriptions").header("Authorization", "Bearer " + settings.getApiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(form).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_NOT_READY);
                        if (status == 429) throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_QUOTA);
                        if (status == 400 || status == 413 || status == 422) throw new AppException(ErrorCode.ORAL_AUDIO_INVALID);
                        if (status != 200) throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_FAILED);
                        byte[] bytes = response.getBody().readNBytes(1024 * 1024 + 1);
                        if (bytes.length > 1024 * 1024) throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_FAILED);
                        return decode(json.readTree(bytes));
                    });
        } catch (AppException safe) { throw safe; }
        catch (Exception failure) { throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_FAILED); }
    }
    /** Không coi hallucination ở đoạn im lặng hoặc response thiếu metadata là câu trả lời thật. */
    static String decode(JsonNode root) {
        try {
            if (!root.path("text").isString() || !root.path("duration").isNumber() || !root.path("segments").isArray()) throw new IllegalArgumentException();
            double duration = root.path("duration").asDouble();
            if (!Double.isFinite(duration) || duration < 0.01 || duration > 305 || root.path("segments").size() > 1000) throw new IllegalArgumentException();
            var text = new StringBuilder(); double previous = 0;
            for (var segment : root.path("segments")) {
                if (!segment.path("text").isString()) throw new IllegalArgumentException();
                double start = number(segment, "start"), end = number(segment, "end"), silence = number(segment, "no_speech_prob"), confidence = number(segment, "avg_logprob");
                if (start < previous || end < start || end > duration + 1 || silence < 0 || silence > 1) throw new IllegalArgumentException();
                previous = start;
                if (silence > 0.6) continue;
                if (confidence < -1.0) throw new IllegalArgumentException();
                String part = segment.path("text").asText().strip();
                if (!part.isEmpty()) { if (!text.isEmpty()) text.append(' '); text.append(part); }
                if (text.length() > 20000 || text.indexOf("\0") >= 0) throw new IllegalArgumentException();
            }
            if (root.path("segments").isEmpty() && !root.path("text").asText().isBlank()) throw new IllegalArgumentException();
            return text.toString();
        } catch (Exception invalid) { throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_FAILED); }
    }
    /** Không dùng giá trị mặc định 0 khi metadata bị thiếu hoặc sai kiểu. */
    private static double number(JsonNode node, String field) {
        if (!node.path(field).isNumber() || !Double.isFinite(node.path(field).asDouble())) throw new IllegalArgumentException();
        return node.path(field).asDouble();
    }
}
