package com.avies.backend.exception;

import com.avies.backend.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(AppException ex) {
        ErrorCode ec = ex.getErrorCode();
        String message = (ex.getMessage() != null && !ex.getMessage().isBlank())
                ? ex.getMessage()
                : ec.getMessage();

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .code(ec.getCode())
                .message(message)
                .build();

        return ResponseEntity.status(ec.getHttpStatusCode()).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        ErrorCode ec = ErrorCode.UNAUTHORIZED;

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .code(ec.getCode())
                .message(ec.getMessage())
                .build();

        return ResponseEntity.status(ec.getHttpStatusCode()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();

        ErrorCode ec = ErrorCode.INVALID_REQUEST;
        String message = ec.getMessage();

        if (fieldError != null) {
            String enumKey = fieldError.getDefaultMessage();
            try {
                ec = ErrorCode.valueOf(enumKey);
                message = ec.getMessage();
            } catch (IllegalArgumentException ignore) {
                message = fieldError.getField() + ": " + enumKey;
            }
        }

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .code(ec.getCode())
                .message(message)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUncategorizedException(Exception ex) {
        log.error("Uncategorized Exception: ", ex);
        ErrorCode ec = ErrorCode.UNCATEGORIZED_EXCEPTION;

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .code(ec.getCode())
                .message(ex.getMessage() != null ? ex.getMessage() : ec.getMessage())
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
