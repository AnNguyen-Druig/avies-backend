package com.avies.backend.entity.enums;

/** Lệnh Student được gửi; không có lệnh tự ghi transcript hoặc tự tạo follow-up. */
public enum OralExamAction {
    /** Đã nghe xong câu đã được worker chuẩn bị. */ PROMPT_FINISHED,
    /** Đọc lại đúng lượt hiện tại. */ REPLAY,
    /** Tạm bỏ qua câu chính chưa trả lời. */ DEFER,
    /** Quay lại câu bỏ qua sau lượt đầu. */ REVISIT,
    /** Kết thúc ghi âm, dành mã xử lý cho worker STT tương lai. */ SUBMIT,
    /** Dừng lượt nói/nghe hiện tại. */ PAUSE,
    /** Tiếp tục hoặc nhận lại lease đã hết hạn. */ RESUME,
    /** Thử lại bước kỹ thuật thất bại, giữ cùng mã xử lý. */ RETRY,
    /** Xác nhận nộp các câu còn tạm bỏ qua. */ FINISH,
    /** Giữ quyền tương tác của tab hiện tại, không gia hạn thời gian thi. */ HEARTBEAT
}
