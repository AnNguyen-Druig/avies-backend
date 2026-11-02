package com.avies.backend.service.support;

import com.avies.backend.exception.AppException;
import com.avies.backend.repository.StudentExamRepository;
import com.avies.backend.service.OralExamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.OffsetDateTime;

/** Chốt lượt quá hạn dù Student đã rời phòng; không tự đóng phiên hoặc đánh vắng lượt chưa bắt đầu. */
@Component @RequiredArgsConstructor @Slf4j
public class ExamAttemptExpiryWorker {
    /** Chỉ đọc tối đa 50 ID ứng viên, không khóa cả danh sách trong transaction dài. */ private final StudentExamRepository exams;
    /** Mỗi lượt tự có transaction/khóa/kiểm tra deadline như thao tác phòng thi. */ private final OralExamService oral;
    /** Dùng cùng đồng hồ máy chủ với Start/Finish. */ private final Clock clock;

    /** Khóa bận sẽ thử ở nhịp sau; một lượt lỗi không chặn việc cập nhật các lượt còn lại. */
    @Scheduled(fixedDelay=5000)
    public void expire() {
        try {
            for (long id:exams.findOverdueAttempts(OffsetDateTime.now(clock))) {
                try { oral.expireOverdue(id); }
                catch (AppException failure) { if (failure.getErrorCode().getCode()!=1303) log.warn("exam-expiry attempt={} code={}",id,failure.getErrorCode().getCode()); }
                catch (RuntimeException failure) { log.warn("exam-expiry attempt={} exceptionType={}",id,failure.getClass().getSimpleName()); }
            }
        } catch (RuntimeException failure) { log.warn("exam-expiry scan exceptionType={}",failure.getClass().getSimpleName()); }
    }
}
