package com.avies.backend.entity.enums;

/** Trạng thái bài thi sinh viên; không mở rộng vòng đời trong P0.3. */
public enum StudentExamStatus {
    /** Sinh viên đã được gán vào phiên thi. */
    ASSIGNED,
    /** Sinh viên đang thực hiện bài thi. */
    IN_PROGRESS,
    /** Sinh viên đã hoàn tất bài thi. */
    COMPLETED
}
