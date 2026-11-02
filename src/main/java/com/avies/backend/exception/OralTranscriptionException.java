package com.avies.backend.exception;

/** Phân loại STT nội bộ; HTTP vẫn chỉ trả mã và thông báo tiếng Việt của AppException. */
@lombok.Getter
public class OralTranscriptionException extends AppException {
    /** Mã chẩn đoán cố định, không chứa audio, transcript hoặc key. */
    public enum Reason { HTTP_ERROR, NETWORK_ERROR, TIMEOUT, INVALID_JSON, RESPONSE_TOO_LARGE,
        INVALID_METADATA, INVALID_TIMESTAMPS, LOW_CONFIDENCE, INVALID_TEXT }
    /** Nguyên nhân máy đọc được trong log BE. */ private final Reason reason;
    /** HTTP status nếu nhà cung cấp có phản hồi. */ private final Integer providerStatus;
    /** Chỉ số đoạn âm thanh lỗi, không phải nội dung câu trả lời. */ private final Integer segmentIndex;
    /** Gắn metadata kiểm soát, không dùng thông báo lỗi thô từ provider. */
    public OralTranscriptionException(ErrorCode code, Reason reason, Integer providerStatus, Integer segmentIndex) {
        super(code); this.reason=reason; this.providerStatus=providerStatus; this.segmentIndex=segmentIndex;
    }
    /** Chỉ lỗi máy chủ tạm thời mới được tự gọi lại; key/quota/quality cần xử lý riêng. */
    public boolean transientServerError() { return reason==Reason.HTTP_ERROR && providerStatus!=null && java.util.Set.of(500,502,503,504).contains(providerStatus); }
}
