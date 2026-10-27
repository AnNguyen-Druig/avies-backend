package com.avies.backend.dto.response;

import com.avies.backend.entity.enums.ExamSessionStatus;

/** Tổng kết đóng phiên không chứa điểm hoặc thay thế kết quả chấm thi. */
public record ClosedExamSessionResponse(
        /** Phiên thuộc quyền giảng viên. */ Long sessionId,
        /** Trạng thái phiên sau đóng. */ ExamSessionStatus status,
        /** Số lượt đã hoàn thành. */ long completedCount,
        /** Số sinh viên chưa bắt đầu khi hết phiên. */ long absentCount,
        /** Số lượt đã bắt đầu nhưng chưa hoàn thành. */ long expiredCount) { }
