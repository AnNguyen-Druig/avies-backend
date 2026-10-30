package com.avies.backend.exam;

import com.avies.backend.config.OralTranscriptionProperties;
import com.avies.backend.exception.*;
import com.avies.backend.repository.OralAudioRepository;
import com.avies.backend.service.*;
import com.avies.backend.service.support.*;
import com.avies.backend.service.impl.GroqOralTranscriptionProviderTest;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Worker không tạo transcript giả khi quota, file hoặc kết quả provider bị lỗi. */
class OralTranscriptionWorkerTest {
    /** Tranh khóa lúc chốt thì gửi lại chính kết quả trong RAM, không gọi Groq lại. */
    @Test void commitConflictRetriesSameResultWithoutSecondProviderCall() {
        var oral=mock(OralExamService.class); var provider=mock(OralTranscriptionProvider.class); var store=mock(OralAudioStore.class);
        var job=UUID.randomUUID(); var work=new OralTranscriptionWork(1L,job,UUID.randomUUID(),"vi","HASH");
        when(oral.claimTranscription(1L,job)).thenReturn(work); when(store.read(job,"HASH")).thenReturn(OralAudioPayload.detect(GroqOralTranscriptionProviderTest.audio()));
        when(provider.transcribe(any(),eq("vi"))).thenReturn("Câu trả lời");
        doThrow(new AppException(ErrorCode.EXAM_SESSION_CONFLICT)).doNothing().when(oral).completeTranscription(work,"Câu trả lời",null);
        new OralTranscriptionWorker(mock(OralTranscriptionProperties.class),mock(OralAudioRepository.class),oral,store,provider,null).process(1L,job);
        verify(provider,times(1)).transcribe(any(),eq("vi")); verify(oral,times(2)).completeTranscription(work,"Câu trả lời",null);
    }
    /** Key/quota lỗi gắn đúng claim, không đổi sang chuỗi rỗng. */
    @Test void quotaFailureIsTechnicalErrorForSameClaim() {
        var oral=mock(OralExamService.class); var provider=mock(OralTranscriptionProvider.class); var store=mock(OralAudioStore.class);
        var job=UUID.randomUUID(); var work=new OralTranscriptionWork(1L,job,UUID.randomUUID(),"vi","HASH");
        when(oral.claimTranscription(1L,job)).thenReturn(work); when(store.read(job,"HASH")).thenReturn(OralAudioPayload.detect(GroqOralTranscriptionProviderTest.audio()));
        when(provider.transcribe(any(),eq("vi"))).thenThrow(new AppException(ErrorCode.ORAL_TRANSCRIPTION_QUOTA));
        var worker=new OralTranscriptionWorker(mock(OralTranscriptionProperties.class),mock(OralAudioRepository.class),oral,store,provider,null);
        worker.process(1L,job); verify(oral).completeTranscription(work,null,1812);
    }
    /** Không có claim thì không được đọc file hoặc gọi dịch vụ ngoài. */
    @Test void losingClaimDoesNotCallProvider() {
        var oral=mock(OralExamService.class); var provider=mock(OralTranscriptionProvider.class); var store=mock(OralAudioStore.class);
        new OralTranscriptionWorker(mock(OralTranscriptionProperties.class),mock(OralAudioRepository.class),oral,store,provider,null).process(1L,UUID.randomUUID());
        verifyNoInteractions(provider,store);
    }
    /** Null là lỗi contract, không được coi như không có lời nói. */
    @Test void missingProviderResultDoesNotCreateEmptyTranscript() {
        var oral=mock(OralExamService.class); var provider=mock(OralTranscriptionProvider.class); var store=mock(OralAudioStore.class);
        var job=UUID.randomUUID(); var work=new OralTranscriptionWork(1L,job,UUID.randomUUID(),"en","HASH");
        when(oral.claimTranscription(1L,job)).thenReturn(work); when(store.read(job,"HASH")).thenReturn(OralAudioPayload.detect(GroqOralTranscriptionProviderTest.audio()));
        new OralTranscriptionWorker(mock(OralTranscriptionProperties.class),mock(OralAudioRepository.class),oral,store,provider,null).process(1L,job);
        verify(oral).completeTranscription(work,null,1811);
    }
}
