package com.avies.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    UNCATEGORIZED_EXCEPTION(9999, "Uncategorized exception", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_KEY(1001, "Invalid message key", HttpStatus.BAD_REQUEST),
    INVALID_REQUEST(1002, "Invalid request", HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(1003, "Unauthenticated", HttpStatus.UNAUTHORIZED),      // 401
    UNAUTHORIZED(1004, "You do not have permission", HttpStatus.FORBIDDEN), // 403
    USER_EXISTED(1005, "Username already exists", HttpStatus.BAD_REQUEST),
    USER_NOT_EXISTED(1006, "User not found", HttpStatus.NOT_FOUND),
    EMAIL_EXISTED(1007, "Email already exists", HttpStatus.BAD_REQUEST),
    ROLE_NOT_EXISTED(1008, "Role does not exist", HttpStatus.NOT_FOUND),
    PASSWORD_TOO_SHORT(1009, "Password must be at least 6 characters", HttpStatus.BAD_REQUEST),
    INVALID_EMAIL(1010, "Email is invalid", HttpStatus.BAD_REQUEST),
    USERNAME_REQUIRED(1011, "Username is required", HttpStatus.BAD_REQUEST),
    PASSWORD_REQUIRED(1012, "Password is required", HttpStatus.BAD_REQUEST),
    FULLNAME_REQUIRED(1013, "Full name is required", HttpStatus.BAD_REQUEST),
    TOKEN_INVALID(1014, "Token is invalid or expired", HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(1015, "Username or Password is incorrect", HttpStatus.UNAUTHORIZED),
    SUBJECT_NOT_EXISTED(1016, "Subject not found", HttpStatus.NOT_FOUND),
    FILE_EMPTY(1017, "Uploaded file cannot be empty", HttpStatus.BAD_REQUEST),
    UNSUPPORTED_FILE_TYPE(1018, "Unsupported file format. Please upload PDF, DOCX, PPTX, or TXT", HttpStatus.BAD_REQUEST),
    FILE_PARSING_FAILED(1019, "Failed to parse content from file", HttpStatus.INTERNAL_SERVER_ERROR),
    OPENAI_API_ERROR(1020, "Failed to generate embeddings via AI service", HttpStatus.INTERNAL_SERVER_ERROR),
    MATERIAL_NOT_EXISTED(1021, "Learning material not found", HttpStatus.NOT_FOUND),
    SUBJECT_EXISTED(1022, "Subject code already exists", HttpStatus.BAD_REQUEST),
    ROLE_NOT_ALLOWED(1023, "Only STUDENT and LECTURER roles are allowed for registration", HttpStatus.BAD_REQUEST),
    NO_RELEVANT_MATERIALS(1024, "Môn học chưa có tài liệu học tập sẵn sàng để sinh câu hỏi", HttpStatus.BAD_REQUEST),
    AI_GENERATION_FAILED(1025, "Không thể sinh câu hỏi từ dịch vụ AI", HttpStatus.INTERNAL_SERVER_ERROR),
    /** Câu hỏi không tồn tại hoặc không nằm trong phạm vi được xem. */
    QUESTION_NOT_FOUND(1100, "Không tìm thấy câu hỏi", HttpStatus.NOT_FOUND),
    SUBJECT_NOT_ASSIGNED(1101, "Bạn chưa được phân công môn học này", HttpStatus.FORBIDDEN),
    QUESTION_NOT_EDITABLE(1102, "Chỉ được sửa hoặc xóa bản nháp chưa lưu trữ", HttpStatus.CONFLICT),
    QUESTION_IN_USE(1103, "Câu hỏi đã được sử dụng trong bài thi", HttpStatus.CONFLICT),
    QUESTION_VERSION_CONFLICT(1104, "Câu hỏi đã thay đổi; hãy tải lại trước khi thao tác", HttpStatus.CONFLICT),
    QUESTION_DATA_CONFLICT(1105, "Dữ liệu đang được tham chiếu hoặc có thao tác đồng thời", HttpStatus.CONFLICT),
    QUESTION_ALREADY_APPROVED(1106, "Câu hỏi này đã được duyệt trước đó", HttpStatus.BAD_REQUEST),
    /** Tài khoản đích tồn tại nhưng không phải giảng viên. */
    ASSIGNMENT_NOT_LECTURER(1200, "Tài khoản được chọn không có vai trò LECTURER", HttpStatus.BAD_REQUEST),
    /** Phân công gặp thay đổi đồng thời hoặc dữ liệu tham chiếu không còn hợp lệ. */
    ASSIGNMENT_CONFLICT(1201, "Phân công đang có thay đổi đồng thời; hãy tải lại và thử lại", HttpStatus.CONFLICT),
    /** Phiên không tồn tại hoặc không thuộc quyền sở hữu được xem. */
    EXAM_SESSION_NOT_FOUND(1300, "Không tìm thấy phiên thi", HttpStatus.NOT_FOUND),
    /** Chỉ phiên UPCOMING mới có thể sửa hoặc xóa. */
    EXAM_SESSION_NOT_EDITABLE(1301, "Chỉ được sửa hoặc xóa phiên thi UPCOMING", HttpStatus.CONFLICT),
    /** Bài thi đã gắn hoặc bắt đầu khiến thao tác thay đổi không còn an toàn. */
    EXAM_SESSION_IN_USE(1302, "Phiên thi đã có bài thi liên quan không cho phép thao tác này", HttpStatus.CONFLICT),
    /** Khóa hoặc dữ liệu phiên thi gặp xung đột; không lộ SQL cho client. */
    EXAM_SESSION_CONFLICT(1303, "Phiên thi hoặc phân công đang thay đổi; hãy tải lại và thử lại", HttpStatus.CONFLICT),
    /** Trạng thái cuối sau khi gộp PATCH có lịch hoặc giới hạn không hợp lệ. */
    EXAM_SESSION_INVALID_SCHEDULE(1304, "Lịch thi phải đầy đủ, bắt đầu trong tương lai, kết thúc sau bắt đầu và các giới hạn phải hợp lệ", HttpStatus.BAD_REQUEST),
    /** Bài thi không tồn tại hoặc không nằm trong quyền sở hữu được thao tác. */
    STUDENT_EXAM_NOT_FOUND(1400, "Không tìm thấy bài thi sinh viên", HttpStatus.NOT_FOUND),
    /** User đích đã tồn tại nhưng hiện không phải sinh viên. */
    ASSIGNMENT_NOT_STUDENT(1401, "Tài khoản được chọn không có vai trò STUDENT", HttpStatus.BAD_REQUEST),
    /** Không có đủ slot nguyên vẹn trong cửa sổ phiên cho toàn bộ batch mới. */
    EXAM_SLOT_CAPACITY_EXCEEDED(1402, "Phiên thi không còn đủ slot cho toàn bộ yêu cầu", HttpStatus.CONFLICT),
    /** Lịch chưa xác định, đã qua hoặc dữ liệu legacy không thể xếp thêm an toàn. */
    EXAM_SCHEDULE_UNAVAILABLE(1403, "Lịch phiên hoặc bài thi cũ chưa hợp lệ để gán thêm sinh viên", HttpStatus.CONFLICT),
    /** Không được xóa bài đã có câu hỏi, thời gian thực tế, điểm hoặc bằng chứng thi. */
    STUDENT_EXAM_IN_USE(1404, "Bài thi đã được sử dụng hoặc có dữ liệu cần bảo toàn", HttpStatus.CONFLICT),
    /** Không đổi cửa sổ lịch hoặc thời lượng slot khi phiên còn bất kỳ StudentExam nào. */
    EXAM_SESSION_SCHEDULE_LOCKED(1405, "Không được đổi lịch phiên khi đã có sinh viên được gán", HttpStatus.CONFLICT);


    private final int code;
    private final String message;
    private final HttpStatus httpStatusCode;

    ErrorCode(int code, String message, HttpStatus httpStatusCode) {
        this.code = code;
        this.message = message;
        this.httpStatusCode = httpStatusCode;
    }
}
