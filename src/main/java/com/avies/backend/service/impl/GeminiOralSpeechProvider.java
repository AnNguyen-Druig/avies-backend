package com.avies.backend.service.impl;

import com.avies.backend.config.OralSpeechProperties;
import com.avies.backend.exception.*;
import com.avies.backend.service.OralSpeechProvider;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** REST Gemini TTS: gọi mạng ngoài transaction DB, không log key, đề hoặc lỗi thô. */
@Service
public class GeminiOralSpeechProvider implements OralSpeechProvider {
    /** Giới hạn WAV khoảng 174 giây PCM mono 24kHz. */ public static final int MAX_AUDIO_BYTES = 8 * 1024 * 1024;
    /** Cấu hình riêng của Feature 3. */ private final OralSpeechProperties settings;
    /** HTTP có timeout hữu hạn, không tự retry tiêu quota khi key/quota lỗi. */ private final RestClient client;
    /** Jackson của Spring Boot hiện tại. */ private final ObjectMapper json = new ObjectMapper();

    /** Chỉ gọi host Google cố định; không nhận URL tùy ý từ người dùng. */
    @org.springframework.beans.factory.annotation.Autowired
    public GeminiOralSpeechProvider(OralSpeechProperties settings) {
        this.settings = settings;
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(5000); factory.setReadTimeout(35000);
        this.client = RestClient.builder().requestFactory(factory).baseUrl("https://generativelanguage.googleapis.com").build();
    }
    /** Constructor package-private phục vụ mock HTTP, không thay URL production qua môi trường. */
    GeminiOralSpeechProvider(OralSpeechProperties settings, RestClient client) { this.settings = settings; this.client = client; }

    /** Interactions API trả WAV mặc định; không bọc thêm header PCM như các model preview cũ. */
    @Override public byte[] synthesize(String text, String language) {
        if (!settings.ready()) throw new AppException(ErrorCode.ORAL_SPEECH_NOT_READY);
        if (text == null || text.isBlank() || text.length() > 11000 || !Set.of("vi", "en").contains(language)
                || !settings.getModel().matches("gemini-[a-zA-Z0-9.\\-]+-tts") || !settings.getVoice().matches("[A-Za-z]{2,30}"))
            throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
        var body = Map.of("model", settings.getModel(), "store", false,
                "input", List.of(Map.of("type", "user_input", "content", List.of(Map.of("type", "text", "text", text,
                        "annotations", List.of(Map.of("type", "speech_metadata", "style", "Clear, neutral examiner. Read the supplied text verbatim without answering, translating or adding commentary.")))))),
                "response_format", Map.of("type", "audio", "mime_type", "audio/wav"),
                "generation_config", Map.of("speech_config", List.of(Map.of("voice", settings.getVoice()))));
        try {
            return client.post().uri("/v1beta/interactions").header("x-goog-api-key", settings.getApiKey())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403 || status == 400) throw new AppException(ErrorCode.ORAL_SPEECH_NOT_READY);
                        if (status == 429) throw new AppException(ErrorCode.ORAL_SPEECH_QUOTA);
                        if (status != 200) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
                        byte[] bytes = response.getBody().readNBytes(12 * 1024 * 1024 + 1);
                        if (bytes.length > 12 * 1024 * 1024) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
                        return decode(json.readTree(bytes));
                    });
        } catch (AppException safe) { throw safe; }
        catch (Exception failure) { throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); }
    }

    /** Chỉ chấp nhận một khối audio từ model, không lấy text hoặc dữ liệu metadata làm âm thanh. */
    static byte[] decode(JsonNode root) {
        try {
            if (!root.path("steps").isArray() || root.path("error").isObject()) throw new IllegalArgumentException();
            String encoded = null;
            for (var step : root.path("steps")) if ("model_output".equals(step.path("type").asText())) {
                for (var part : step.path("content")) if ("audio".equals(part.path("type").asText())) {
                    if (encoded != null || !part.path("data").isString()) throw new IllegalArgumentException();
                    encoded = part.path("data").asText();
                }
            }
            if (encoded == null || encoded.length() > (MAX_AUDIO_BYTES * 4L / 3 + 4)) throw new IllegalArgumentException();
            byte[] wav = Base64.getDecoder().decode(encoded); validateWav(wav); return wav;
        } catch (Exception invalid) { throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); }
    }
    /** Kiểm tra RIFF/WAVE PCM và kích thước chunk trước khi trình duyệt phát dữ liệu bên ngoài. */
    public static void validateWav(byte[] wav) {
        if (wav == null || wav.length < 46 || wav.length > MAX_AUDIO_BYTES) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
        var b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157 || Integer.toUnsignedLong(b.getInt(4)) + 8 != wav.length)
            throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
        boolean format = false, data = false;
        for (int offset = 12; offset + 8 <= wav.length;) {
            long length = Integer.toUnsignedLong(b.getInt(offset + 4)); long end = offset + 8L + length;
            if (end > wav.length) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
            if (b.getInt(offset) == 0x20746d66) {
                if (length < 16 || b.getShort(offset + 8) != 1 || b.getShort(offset + 10) != 1 || b.getInt(offset + 12) != 24000
                        || b.getInt(offset + 16) != 48000 || b.getShort(offset + 20) != 2 || b.getShort(offset + 22) != 16)
                    throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
                format = true;
            }
            if (b.getInt(offset) == 0x61746164) { if (length < 2 || length % 2 != 0) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); data = true; }
            offset = Math.toIntExact(end + length % 2);
        }
        if (!format || !data) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
    }
}
