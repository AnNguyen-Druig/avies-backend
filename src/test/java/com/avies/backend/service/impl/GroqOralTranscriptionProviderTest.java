package com.avies.backend.service.impl;

import com.avies.backend.config.OralTranscriptionProperties;
import com.avies.backend.exception.AppException;
import com.avies.backend.service.support.OralAudioPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Hợp đồng Groq thực thi bằng HTTP stub, không đọc key thật hoặc gửi giọng nói ra internet. */
public class GroqOralTranscriptionProviderTest {
    /** WAV fixture đủ container/size, không giả là audio người dùng. */
    public static byte[] audio() {
        byte[] data = new byte[256]; System.arraycopy("RIFF".getBytes(), 0, data, 0, 4); System.arraycopy("WAVE".getBytes(), 0, data, 8, 4); return data;
    }
    /** JSON verbose fixture có metadata kiểm tra tín hiệu. */
    private static String response(String text, double silence) {
        return "{\"text\":\""+text+"\",\"duration\":1.2,\"segments\":[{\"text\":\""+text+"\",\"start\":0,\"end\":1.2,\"no_speech_prob\":"+silence+",\"avg_logprob\":-0.1}]}";
    }
    /** Transport trong bộ nhớ. */ private MockRestServiceServer server;
    /** Provider production. */ private GroqOralTranscriptionProvider provider;
    /** Key cấu hình giả. */ private OralTranscriptionProperties settings;
    /** Không mở socket tới Groq. */
    @BeforeEach void setup() {
        settings = mock(OralTranscriptionProperties.class); when(settings.ready()).thenReturn(true);
        when(settings.getApiKey()).thenReturn("fixture-groq-key"); when(settings.getModel()).thenReturn("whisper-large-v3-turbo");
        var builder=RestClient.builder().baseUrl("https://api.groq.com"); server=MockRestServiceServer.bindTo(builder).build(); provider=new GroqOralTranscriptionProvider(settings,builder.build());
    }
    /** Chỉ transcription endpoint, multipart file, không gửi prompt đáp án hoặc gọi translations. */
    @Test void preservesVietnameseTextAndUsesTranscriptionEndpoint() {
        server.expect(requestTo("https://api.groq.com/openai/v1/audio/transcriptions")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization","Bearer fixture-groq-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("whisper-large-v3-turbo"), org.hamcrest.Matchers.containsString("verbose_json"), org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("reference_answer")))))
                .andRespond(withSuccess(response("Em không biết",0.01), MediaType.APPLICATION_JSON));
        assertThat(provider.transcribe(OralAudioPayload.detect(audio()),"vi")).isEqualTo("Em không biết"); server.verify();
    }
    /** Phân biệt key/quota/audio/mạng, không trả lỗi thô chứa thông tin nhà cung cấp. */
    @ParameterizedTest @ValueSource(ints={400,401,403,413,422,429,500}) void providerFailuresAreSafe(int status) {
        server.expect(requestTo("https://api.groq.com/openai/v1/audio/transcriptions")).andRespond(withStatus(HttpStatus.valueOf(status)).body("PRIVATE_KEY_PROVIDER_BODY"));
        assertThatThrownBy(() -> provider.transcribe(OralAudioPayload.detect(audio()),"en")).isInstanceOfSatisfying(AppException.class, e -> {
            assertThat(e.getErrorCode().getCode()).isEqualTo(status==429?1812:status==401||status==403?1810:status==400||status==413||status==422?1813:1811);
            assertThat(e.getMessage()).doesNotContain("PRIVATE");
        }); server.verify();
    }
    /** Không ghi text hallucination của đoạn im lặng làm bằng chứng Student. */
    @Test void highNoSpeechProbabilityReturnsEmptyInsteadOfHallucination() throws Exception {
        assertThat(GroqOralTranscriptionProvider.decode(new ObjectMapper().readTree(response("Thank you for watching",0.99)))).isEmpty();
    }
    /** Metadata bị thiếu/sai không được giả thành transcript rỗng. */
    @ParameterizedTest @ValueSource(strings={"{}","{\"text\":\"answer\",\"duration\":1,\"segments\":[]}","{\"text\":\"answer\",\"duration\":1,\"segments\":[{\"text\":\"answer\",\"start\":0,\"end\":1}]}"})
    void malformedResponseIsTechnicalError(String json) {
        assertThatThrownBy(() -> GroqOralTranscriptionProvider.decode(new ObjectMapper().readTree(json))).isInstanceOf(AppException.class);
    }
    /** Flag tắt thì không gửi audio ra ngoài. */
    @Test void disabledDoesNotCallGroq() {
        when(settings.ready()).thenReturn(false);
        assertThatThrownBy(() -> provider.transcribe(OralAudioPayload.detect(audio()),"vi")).isInstanceOf(AppException.class); server.verify();
    }
}
