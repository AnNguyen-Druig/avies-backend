package com.avies.backend.exam;

import com.avies.backend.dto.response.OralExamResponse;
import com.avies.backend.entity.enums.OralExamPhase;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.service.OralExamService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Chỉ kiểm tra worker fixture; không mở Docker, HTTP hoặc dịch vụ AI. */
class OralExamBrowserDemoTest {
    /** Demo không nạp key/flag TTS/STT từ môi trường, không gọi nhà cung cấp ngoài ý muốn. */
    @Test void demoRegistersDisabledSpeechConfigurationWithoutSecrets() {
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        OralExamBrowserDemo.DemoConfig.demoDecoder().postProcessBeanFactory(factory);
        var settings = factory.getBean(com.avies.backend.config.OralSpeechProperties.class);
        org.assertj.core.api.Assertions.assertThat(settings.ready()).isFalse();
        org.assertj.core.api.Assertions.assertThat(settings.getApiKey()).isNull();
        var transcription = factory.getBean(com.avies.backend.config.OralTranscriptionProperties.class);
        org.assertj.core.api.Assertions.assertThat(transcription.ready()).isFalse();
        org.assertj.core.api.Assertions.assertThat(transcription.getApiKey()).isNull();
        var decision=factory.getBean(com.avies.backend.config.OralDecisionProperties.class);
        org.assertj.core.api.Assertions.assertThat(decision.ready()).isFalse();
        org.assertj.core.api.Assertions.assertThat(decision.getApiKey()).isNull();
    }
    /** Callback chưa commit do tranh khóa thì không được đánh mất tình huống lỗi đã chọn. */
    @Test void technicalFailureScenarioSurvivesCallbackRollback() {
        var jdbc = mock(JdbcTemplate.class); var oral = mock(OralExamService.class);
        var turn = UUID.randomUUID(); var job = UUID.randomUUID();
        var state = mock(OralExamResponse.class);
        when(state.phase()).thenReturn(OralExamPhase.ANSWERING);
        when(state.currentTurnId()).thenReturn(turn); when(oral.get(1L)).thenReturn(state);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(1L));
        when(jdbc.queryForMap(anyString(), eq(1L))).thenReturn(Map.of("phase", "TRANSCRIBING", "current_turn_id", turn, "language", "vi", "question_id", 1L));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(turn))).thenReturn(List.of(job));
        doThrow(new AppException(ErrorCode.ORAL_STALE_COMMAND)).doReturn(null).when(oral).technicalFailure(1L, job);
        try (var worker = new OralExamBrowserDemo.DemoPipeline(jdbc, oral)) {
            worker.choose(1L, new OralExamBrowserDemo.DemoChoice(turn, OralExamBrowserDemo.Scenario.TECHNICAL_ERROR));
            worker.tick(); worker.tick();
            verify(oral, times(2)).technicalFailure(1L, job);
            verify(oral, never()).transcriptReady(anyLong(), any(), anyString());
        }
    }
}
