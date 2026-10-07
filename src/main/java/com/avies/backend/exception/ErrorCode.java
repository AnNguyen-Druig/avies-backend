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
    QUESTION_NOT_FOUND(1100, "Không tìm thấy câu hỏi", HttpStatus.NOT_FOUND),
    SUBJECT_NOT_ASSIGNED(1101, "Bạn chưa được phân công môn học này", HttpStatus.FORBIDDEN),
    QUESTION_NOT_EDITABLE(1102, "Chỉ được sửa hoặc xóa bản nháp chưa lưu trữ", HttpStatus.CONFLICT),
    QUESTION_IN_USE(1103, "Câu hỏi đã được sử dụng trong bài thi", HttpStatus.CONFLICT),
    QUESTION_VERSION_CONFLICT(1104, "Câu hỏi đã thay đổi; hãy tải lại trước khi thao tác", HttpStatus.CONFLICT),
    QUESTION_DATA_CONFLICT(1105, "Dữ liệu đang được tham chiếu hoặc có thao tác đồng thời", HttpStatus.CONFLICT);


    private final int code;
    private final String message;
    private final HttpStatus httpStatusCode;

    ErrorCode(int code, String message, HttpStatus httpStatusCode) {
        this.code = code;
        this.message = message;
        this.httpStatusCode = httpStatusCode;
    }
}
