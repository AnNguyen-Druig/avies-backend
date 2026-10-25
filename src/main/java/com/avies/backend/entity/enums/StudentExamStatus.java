package com.avies.backend.entity.enums;

/** Vòng đời lượt thi; vắng thi và hết giờ không được giả thành hoàn thành bài. */
public enum StudentExamStatus {
    /** Sinh viên đã được gán vào phiên thi. */
    ASSIGNED,
    /** Sinh viên đang thực hiện bài thi. */
    IN_PROGRESS,
    /** Sinh viên đã hoàn tất bài thi. */
    COMPLETED,
    /** Hết phiên nhưng sinh viên chưa bắt đầu lượt thi. */
    ABSENT,
    /** Đã bắt đầu nhưng chưa hoàn thành khi giảng viên đóng phiên hết giờ. */
    EXPIRED
}
