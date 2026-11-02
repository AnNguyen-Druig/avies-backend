package com.avies.backend.exam;

import com.avies.backend.config.OralSpeechProperties;
import com.avies.backend.dto.request.*;
import com.avies.backend.exception.*;
import com.avies.backend.service.*;
import com.avies.backend.service.impl.*;
import com.avies.backend.service.support.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Cache và thứ tự kiểm tra quyền không phụ thuộc Docker hoặc provider thật. */
class OralSpeechServiceTest {
    /** Quota warmup không bị yêu cầu nghe gọi lại ngay; sau cooldown vẫn retry được và không mở giờ trả lời. */
    @Test void quotaCooldownAvoidsRepeatedProviderCallsAndExpires() {
        var oral=mock(OralExamService.class); var provider=mock(OralSpeechProvider.class); var settings=mock(OralSpeechProperties.class);
        var clock=mock(Clock.class); var instant=Instant.parse("2026-11-02T00:00:00Z"); when(clock.instant()).thenReturn(instant); when(settings.ready()).thenReturn(true);
        var plan=OralSpeechPlan.from(UUID.randomUUID(),"vi",1,0,"Câu hỏi thử hạn mức?");
        var request=new OralSpeechRequest(UUID.randomUUID(),plan.turnId(),0L); when(oral.speechPlan(1L,request)).thenReturn(plan);
        when(provider.synthesize(anyString(),anyString())).thenThrow(new AppException(ErrorCode.ORAL_SPEECH_QUOTA)).thenReturn(GeminiOralSpeechProviderTest.wav());
        var service=new OralSpeechServiceImpl(oral,provider,mock(ExamAccessPolicy.class),settings,clock);
        assertThatThrownBy(() -> service.prefetch(plan)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.current(1L,request)).isInstanceOfSatisfying(AppException.class,e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORAL_SPEECH_QUOTA));
        verify(provider,times(1)).synthesize(anyString(),anyString()); verify(oral,never()).confirmSpeech(anyLong(),any(),anyString());
        when(clock.instant()).thenReturn(instant.plusSeconds(31));
        assertThat(service.current(1L,request)).isEqualTo(GeminiOralSpeechProviderTest.wav());
        verify(provider,times(2)).synthesize(anyString(),anyString()); verify(oral).confirmSpeech(1L,request,"vi");
    }

    /** Lỗi mạng không bị giữ như quota, lần nghe tiếp vẫn gọi lại provider ngay. */
    @Test void transientSpeechFailureIsNotCachedAsQuota() {
        var settings=mock(OralSpeechProperties.class); var provider=mock(OralSpeechProvider.class); when(settings.ready()).thenReturn(true);
        when(provider.synthesize(anyString(),anyString())).thenThrow(new AppException(ErrorCode.ORAL_SPEECH_FAILED)).thenReturn(GeminiOralSpeechProviderTest.wav());
        var service=new OralSpeechServiceImpl(mock(OralExamService.class),provider,mock(ExamAccessPolicy.class),settings,Clock.systemUTC());
        var plan=OralSpeechPlan.from(UUID.randomUUID(),"en",1,0,"Explain encapsulation?");
        assertThatThrownBy(() -> service.prefetch(plan)).isInstanceOf(AppException.class); service.prefetch(plan);
        verify(provider,times(2)).synthesize(anyString(),anyString());
    }
    /** Hai lượt khác nhau có cùng lời đọc dùng lại audio nhưng mỗi lượt vẫn phải xác thực/confirm riêng. */
    @Test void identicalPromptsAcrossAttemptsReuseAudioAndReauthorize() {
        var oral=mock(OralExamService.class); var provider=mock(OralSpeechProvider.class); var settings=mock(OralSpeechProperties.class);
        when(settings.ready()).thenReturn(true);
        var first=OralSpeechPlan.from(UUID.randomUUID(),"vi",1,0,"Giải thích kiến trúc phần mềm.");
        var second=OralSpeechPlan.from(UUID.randomUUID(),"vi",1,0,"Giải thích kiến trúc phần mềm.");
        var request1=new OralSpeechRequest(UUID.randomUUID(),first.turnId(),0L); var request2=new OralSpeechRequest(UUID.randomUUID(),second.turnId(),0L);
        when(oral.speechPlan(1L,request1)).thenReturn(first); when(oral.speechPlan(2L,request2)).thenReturn(second);
        when(provider.synthesize(anyString(),anyString())).thenReturn(GeminiOralSpeechProviderTest.wav());
        var service=new OralSpeechServiceImpl(oral,provider,mock(ExamAccessPolicy.class),settings,Clock.systemUTC());
        service.prefetch(first); service.current(1L,request1); service.current(2L,request2);
        verify(provider,times(1)).synthesize(first.text(),"vi"); verify(oral).confirmSpeech(1L,request1,"vi"); verify(oral).confirmSpeech(2L,request2,"vi");
        when(oral.speechPlan(2L,request2)).thenThrow(new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        assertThatThrownBy(() -> service.current(2L,request2)).isInstanceOf(AppException.class); verifyNoMoreInteractions(provider);
    }
    /** Cache không được dùng audio có sai số câu, ngôn ngữ, voice hoặc model. */
    @Test void speechCacheIncludesAnnouncementLanguageVoiceAndModel() {
        var settings=mock(OralSpeechProperties.class); var provider=mock(OralSpeechProvider.class); when(settings.ready()).thenReturn(true);
        when(settings.getModel()).thenReturn("tts-a"); when(settings.getVoice()).thenReturn("Kore");
        when(provider.synthesize(anyString(),anyString())).thenReturn(GeminiOralSpeechProviderTest.wav());
        var service=new OralSpeechServiceImpl(mock(OralExamService.class),provider,mock(ExamAccessPolicy.class),settings,Clock.systemUTC());
        var first=OralSpeechPlan.from(UUID.randomUUID(),"vi",1,0,"Câu hỏi về API?");
        service.prefetch(first); service.prefetch(OralSpeechPlan.from(UUID.randomUUID(),"vi",2,0,"Câu hỏi về API?"));
        service.prefetch(new OralSpeechPlan(UUID.randomUUID(),"en",first.text()));
        when(settings.getVoice()).thenReturn("Puck"); service.prefetch(first);
        when(settings.getModel()).thenReturn("tts-b"); service.prefetch(first);
        verify(provider,times(5)).synthesize(anyString(),anyString());
    }
    /** Prefetch không mở giờ trả lời; yêu cầu nghe thật dùng cache nhưng vẫn xác thực hai lần. */
    @Test void warmupReusesAudioWithoutOpeningTurn() {
        var oral=mock(OralExamService.class); var provider=mock(OralSpeechProvider.class); var settings=mock(OralSpeechProperties.class);
        when(settings.ready()).thenReturn(true);
        var plan=OralSpeechPlan.from(UUID.randomUUID(),"vi",2,0,"Giải thích kiến trúc phần mềm.");
        var request=new OralSpeechRequest(UUID.randomUUID(),plan.turnId(),3L);
        when(provider.synthesize(plan.text(),plan.language())).thenReturn(GeminiOralSpeechProviderTest.wav());
        when(oral.speechPlan(1L,request)).thenReturn(plan);
        var service=new OralSpeechServiceImpl(oral,provider,mock(ExamAccessPolicy.class),settings,Clock.systemUTC());
        service.prefetch(plan); verifyNoInteractions(oral);
        assertThat(service.current(1L,request)).isEqualTo(GeminiOralSpeechProviderTest.wav());
        verify(provider,times(1)).synthesize(plan.text(),plan.language()); verify(oral).confirmSpeech(1L,request,"vi");
    }
    /** Chưa bật TTS thì không phát sinh cuộc gọi làm nóng. */
    @Test void disabledWarmupDoesNotCallProvider() {
        var oral=mock(OralExamService.class); var provider=mock(OralSpeechProvider.class);
        new OralSpeechServiceImpl(oral,provider,mock(ExamAccessPolicy.class),mock(OralSpeechProperties.class),Clock.systemUTC())
                .prefetch(OralSpeechPlan.from(UUID.randomUUID(),"en",1,0,"Question?"));
        verifyNoInteractions(oral,provider);
    }
    /** Nghe lại dùng cùng audio nhưng vẫn kiểm tra quyền trước và sau mỗi lần. */
    @Test void replayReusesAudioAndReauthorizesEveryRequest() {
        var oral = mock(OralExamService.class); var provider = mock(OralSpeechProvider.class); var access = mock(ExamAccessPolicy.class);
        var settings = mock(OralSpeechProperties.class); when(settings.ready()).thenReturn(true);
        var turn = UUID.randomUUID(); var request = new OralSpeechRequest(UUID.randomUUID(), turn, 0L);
        var plan = OralSpeechPlan.from(turn, null, 2, 0, "Định nghĩa kiểm thử phần mềm.");
        when(oral.speechPlan(1L, request)).thenReturn(plan);
        when(provider.synthesize(anyString(), anyString())).thenReturn(GeminiOralSpeechProviderTest.wav());
        var service = new OralSpeechServiceImpl(oral, provider, access, settings, Clock.systemUTC());
        service.current(1L, request); service.current(1L, request);
        verify(provider).synthesize(plan.text(), "vi"); verify(oral, times(2)).speechPlan(1L, request); verify(oral, times(2)).confirmSpeech(1L, request, "vi");
        when(oral.speechPlan(1L, request)).thenThrow(new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        assertThatThrownBy(() -> service.current(1L, request)).isInstanceOf(AppException.class); verifyNoMoreInteractions(provider);
    }
    /** Hai yêu cầu song song cùng turn chỉ tiêu một lần tổng hợp. */
    @Test void concurrentRequestsShareOneSynthesis() throws Exception {
        var oral = mock(OralExamService.class); var provider = mock(OralSpeechProvider.class); var settings = mock(OralSpeechProperties.class);
        when(settings.ready()).thenReturn(true); var turn = UUID.randomUUID(); var request = new OralSpeechRequest(UUID.randomUUID(), turn, 0L);
        when(oral.speechPlan(1L, request)).thenReturn(OralSpeechPlan.from(turn, null, 1, 0, "Explain inheritance."));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(provider.synthesize(anyString(), anyString())).thenAnswer(i -> { entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return GeminiOralSpeechProviderTest.wav(); });
        var service = new OralSpeechServiceImpl(oral, provider, mock(ExamAccessPolicy.class), settings, Clock.systemUTC());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.current(1L, request)); assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> service.current(1L, request)); release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS)).containsExactly(second.get(3, TimeUnit.SECONDS));
        }
        verify(provider).synthesize(anyString(), eq("en"));
    }
    /** Lời dẫn đúng thứ tự, follow-up giữ ngôn ngữ của câu chính. */
    @Test void announcementsUseStableQuestionNumberAndInheritedLanguage() {
        var id = UUID.randomUUID();
        assertThat(OralSpeechPlan.from(id, null, 2, 0, "Câu hỏi về kiến trúc").text()).startsWith("Câu số 2. ");
        assertThat(OralSpeechPlan.from(id, null, 3, 0, "Explain polymorphism").text()).startsWith("Question number 3. ");
        assertThat(OralSpeechPlan.from(id, "vi", 2, 1, "API example?").text()).startsWith("Câu số 2, câu hỏi đào sâu 1. ");
    }
}
