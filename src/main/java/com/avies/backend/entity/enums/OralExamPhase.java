package com.avies.backend.entity.enums;

/** Các bước do máy chủ điều phối, tách khỏi trạng thái toàn lượt thi Feature 2. */
public enum OralExamPhase {
    /** Chờ worker chuẩn bị giọng đọc cho đúng câu hiện tại. */ PREPARING,
    /** Câu đã sẵn sàng phát; chưa tính thời gian trả lời. */ SPEAKING,
    /** Đang dành thời gian cho sinh viên trả lời, kể cả im lặng. */ ANSWERING,
    /** Chờ STT xử lý lượt audio đã nộp. */ TRANSCRIBING,
    /** Đã lưu transcript, chờ quyết định hỏi tiếp. */ DECIDING,
    /** Đã hết lượt đầu, còn câu tạm bỏ qua. */ REVIEW,
    /** Dừng tương tác nhưng thời hạn toàn bài vẫn chạy. */ PAUSED,
    /** Lỗi kỹ thuật có thể thử lại, không suy diễn thành không biết trả lời. */ TECHNICAL_ERROR,
    /** Đủ kết quả từng câu hoặc đã xác nhận nộp các câu bỏ qua. */ COMPLETED,
    /** Hết slot, không được gia hạn hoặc đổi thành hoàn thành. */ EXPIRED
}
