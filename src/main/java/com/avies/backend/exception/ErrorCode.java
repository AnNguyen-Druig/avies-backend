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
    INVALID_CREDENTIALS(1015, "Username or Password is incorrect", HttpStatus.UNAUTHORIZED);


    private final int code;
    private final String message;
    private final HttpStatus httpStatusCode;

    ErrorCode(int code, String message, HttpStatus httpStatusCode) {
        this.code = code;
        this.message = message;
        this.httpStatusCode = httpStatusCode;
    }
}
