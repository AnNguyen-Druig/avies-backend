package com.avies.backend.service.impl;

import com.avies.backend.config.OralSpeechProperties;
import com.avies.backend.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import java.nio.*;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Kiểm tra hợp đồng Gemini bằng HTTP stub; không đọc key thật hoặc gọi internet. */
public class GeminiOralSpeechProviderTest {
    /** Nhà cung cấp thật dùng transport stub. */ private GeminiOralSpeechProvider provider;
    /** Quan sát request mà không ra mạng. */ private MockRestServiceServer server;
    /** Cấu hình fixture. */ private OralSpeechProperties settings;
    /** Một WAV PCM hợp lệ, không giả là giọng nói thật. */
    public static byte[] wav() {
        var b = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(40).putInt(0x45564157).putInt(0x20746d66).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(24000).putInt(48000).putShort((short) 2).putShort((short) 16)
                .putInt(0x61746164).putInt(4).putInt(0);
        return b.array();
    }
    /** Chỉ dùng chuỗi key fixture. */
    @BeforeEach void setup() {
        settings = mock(OralSpeechProperties.class); when(settings.ready()).thenReturn(true);
        when(settings.getApiKey()).thenReturn("fixture-key"); when(settings.getModel()).thenReturn("gemini-3.8-flash-tts"); when(settings.getVoice()).thenReturn("Kore");
        var builder = RestClient.builder().baseUrl("https://generativelanguage.googleapis.com");
        server = MockRestServiceServer.bindTo(builder).build(); provider = new GeminiOralSpeechProvider(settings, builder.build());
    }
    /** Model đọc nguyên văn theo Interactions API và trả đúng byte WAV, không thêm header lần hai. */
    @Test void sendsVerbatimPromptAndReturnsValidatedWave() {
        String body = "{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"data\":\"" + Base64.getEncoder().encodeToString(wav()) + "\"}]}]}";
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/interactions")).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "fixture-key"))
                .andExpect(jsonPath("$.input[0].content[0].text").value("Câu số 1. Nội dung snapshot."))
                .andExpect(jsonPath("$.response_format.mime_type").value("audio/wav"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(provider.synthesize("Câu số 1. Nội dung snapshot.", "vi")).containsExactly(wav()); server.verify();
    }
    /** Lỗi ngoài dịch vụ không lộ key hoặc nội dung response tiếng Anh. */
    @ParameterizedTest @ValueSource(ints = {400,401,403,429,500}) void mapsProviderErrorsWithoutExposingBody(int status) {
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/interactions"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("PRIVATE_KEY_OR_PROVIDER_ERROR"));
        assertThatThrownBy(() -> provider.synthesize("Question number 1. Explain an interface.", "en"))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo(status == 429 ? 1809 : status < 500 ? 1807 : 1808);
                    assertThat(e.getMessage()).doesNotContain("PRIVATE");
                }); server.verify();
    }
    /** Audio rỗng, JSON sai hoặc base64 không phải WAV đều bị chặn. */
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"steps\":[]}", "{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"data\":\"invalid!\"}]}]}"})
    void rejectsMalformedAudio(String json) throws Exception {
        assertThatThrownBy(() -> GeminiOralSpeechProvider.decode(new ObjectMapper().readTree(json))).isInstanceOf(AppException.class);
    }
    /** Không mở request nếu TTS chưa bật. */
    @Test void disabledDoesNotCallProvider() {
        when(settings.ready()).thenReturn(false);
        assertThatThrownBy(() -> provider.synthesize("Question number 1.", "en")).isInstanceOf(AppException.class); server.verify();
    }
    /** Header RIFF không đủ để tin PCM; chặn sample rate/size bị sửa. */
    @Test void rejectsInvalidWaveHeaderAndTruncatedData() {
        byte[] invalid = wav(); ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 48000);
        assertThatThrownBy(() -> GeminiOralSpeechProvider.validateWav(invalid)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> GeminiOralSpeechProvider.validateWav(new byte[0])).isInstanceOf(AppException.class);
    }
}
