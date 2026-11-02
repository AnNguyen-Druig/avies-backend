package com.avies.backend.exam;

import com.avies.backend.exception.*;
import com.avies.backend.repository.StudentExamRepository;
import com.avies.backend.service.OralExamService;
import com.avies.backend.service.support.ExamAttemptExpiryWorker;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.mockito.Mockito.*;

/** Tác vụ hết giờ không phụ thuộc key AI, JWT hoặc tab Student còn mở. */
class ExamAttemptExpiryWorkerTest {
    /** Không có ứng viên thì chỉ có một query nhẹ, không gọi service hoặc provider. */
    @Test void emptyScanDoesNotTouchAttempts() {
        var exams=mock(StudentExamRepository.class); var oral=mock(OralExamService.class);
        new ExamAttemptExpiryWorker(exams,oral,Clock.systemUTC()).expire(); verifyNoInteractions(oral);
    }
    /** Dùng đồng hồ BE và tiếp tục batch sau xung đột với Finish hoặc một lượt lỗi. */
    @Test void busyOrBrokenAttemptDoesNotBlockOtherCandidates() {
        var exams=mock(StudentExamRepository.class); var oral=mock(OralExamService.class);
        var instant=Instant.parse("2026-11-02T05:00:00Z"); var clock=Clock.fixed(instant,ZoneOffset.UTC);
        when(exams.findOverdueAttempts(OffsetDateTime.ofInstant(instant,ZoneOffset.UTC))).thenReturn(List.of(1L,2L,3L));
        when(oral.expireOverdue(1L)).thenThrow(new AppException(ErrorCode.EXAM_SESSION_CONFLICT));
        when(oral.expireOverdue(2L)).thenThrow(new IllegalStateException("Fixture failure"));
        new ExamAttemptExpiryWorker(exams,oral,clock).expire();
        verify(oral).expireOverdue(1L); verify(oral).expireOverdue(2L); verify(oral).expireOverdue(3L);
    }
    /** Lỗi đọc datasource được giữ trong tác vụ nền, không làm scheduler ngừng các nhịp tiếp theo. */
    @Test void failedScanCanRunAgain() {
        var exams=mock(StudentExamRepository.class); var oral=mock(OralExamService.class);
        when(exams.findOverdueAttempts(any())).thenThrow(new IllegalStateException("Offline fixture")).thenReturn(List.of(3L));
        var worker=new ExamAttemptExpiryWorker(exams,oral,Clock.systemUTC()); worker.expire(); worker.expire();
        verify(oral).expireOverdue(3L);
    }
}
