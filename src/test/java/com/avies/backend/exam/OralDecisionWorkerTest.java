package com.avies.backend.exam;

import com.avies.backend.config.OralDecisionProperties;
import com.avies.backend.exception.*;
import com.avies.backend.repository.OralDecisionRepository;
import com.avies.backend.service.*;
import com.avies.backend.service.support.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;

/** Lỗi AI không thành ADVANCE giả, hết quota follow-up không tiêu thêm API. */
class OralDecisionWorkerTest {
    /** Kết quả AI đã có không bị bỏ khi callback tranh khóa với request khác. */
    @Test void commitConflictDoesNotCallAiAgain() {
        var oral=mock(OralExamService.class); var provider=mock(OralDecisionProvider.class); var work=work(true);
        when(oral.claimDecision(1L,work.submissionId())).thenReturn(work); when(provider.decide(work)).thenReturn(OralDecisionResult.advance());
        doThrow(new AppException(ErrorCode.EXAM_SESSION_CONFLICT)).doNothing().when(oral).completeDecision(work,OralDecisionResult.advance(),null);
        new OralDecisionWorker(mock(OralDecisionProperties.class),mock(OralDecisionRepository.class),oral,provider,null).process(1,work.submissionId());
        verify(provider,times(1)).decide(work); verify(oral,times(2)).completeDecision(work,OralDecisionResult.advance(),null);
    }
    /** Context fixture không có key hoặc dữ liệu thật. */
    private OralDecisionWork work(boolean follow) { return new OralDecisionWork(1,UUID.randomUUID(),UUID.randomUUID(),"en","Question?",List.of(new OralDecisionWork.Exchange("Question?","Answer")),follow); }
    /** Chỉ holder claim mới được gọi AI. */
    @Test void lostClaimDoesNotCallProvider() {
        var oral=mock(OralExamService.class); var provider=mock(OralDecisionProvider.class);
        new OralDecisionWorker(mock(OralDecisionProperties.class),mock(OralDecisionRepository.class),oral,provider,null).process(1,UUID.randomUUID()); verifyNoInteractions(provider);
    }
    /** Không còn follow-up thì chuyển câu theo business rule, không gọi AI giả. */
    @Test void exhaustedQuotaAdvancesWithoutProvider() {
        var oral=mock(OralExamService.class); var provider=mock(OralDecisionProvider.class); var work=work(false);
        when(oral.claimDecision(1L,work.submissionId())).thenReturn(work);
        new OralDecisionWorker(mock(OralDecisionProperties.class),mock(OralDecisionRepository.class),oral,provider,null).process(1,work.submissionId());
        verify(oral).completeDecision(work,OralDecisionResult.advance(),null); verifyNoInteractions(provider);
    }
    /** Quota dịch vụ lỗi giữ đúng công việc và mã kỹ thuật. */
    @Test void providerQuotaDoesNotInventAdvance() {
        var oral=mock(OralExamService.class); var provider=mock(OralDecisionProvider.class); var work=work(true);
        when(oral.claimDecision(1L,work.submissionId())).thenReturn(work); when(provider.decide(work)).thenThrow(new AppException(ErrorCode.ORAL_DECISION_QUOTA));
        new OralDecisionWorker(mock(OralDecisionProperties.class),mock(OralDecisionRepository.class),oral,provider,null).process(1,work.submissionId()); verify(oral).completeDecision(work,null,1817);
    }
    /** Thiếu kết quả là contract lỗi, không tự chuyển câu. */
    @Test void nullResultIsTechnical() {
        var oral=mock(OralExamService.class); var provider=mock(OralDecisionProvider.class); var work=work(true);
        when(oral.claimDecision(1L,work.submissionId())).thenReturn(work);
        new OralDecisionWorker(mock(OralDecisionProperties.class),mock(OralDecisionRepository.class),oral,provider,null).process(1,work.submissionId()); verify(oral).completeDecision(work,null,1816);
    }
}
