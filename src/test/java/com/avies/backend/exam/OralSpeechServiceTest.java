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
