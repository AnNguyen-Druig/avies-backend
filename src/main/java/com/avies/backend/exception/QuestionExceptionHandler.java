package com.avies.backend.exception;

import com.avies.backend.controller.QuestionController;
import com.avies.backend.dto.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.beans.TypeMismatchException;

/**
 * Chuẩn hóa lỗi riêng cho API câu hỏi và không để lộ SQL hoặc chi tiết máy chủ.
 */
@RestControllerAdvice(assignableTypes = QuestionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class QuestionExceptionHandler {
    /**
     * Trả lỗi nghiệp vụ đã được xác định trong service.
     */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> business(AppException exception) {
        return response(exception.getErrorCode(), exception.getMessage());
    }

    /**
     * Chặn tài khoản không có vai trò được sử dụng chức năng.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden(AccessDeniedException exception) {
        return response(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getMessage());
    }

    /**
     * Trả lỗi dữ liệu đầu vào mà không phản chiếu nội dung hoặc cấu trúc nội bộ của exception.
     */
    @ExceptionHandler({BindException.class, ConstraintViolationException.class, HandlerMethodValidationException.class,
            TypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception exception) {
        return response(ErrorCode.INVALID_REQUEST, "Dữ liệu câu hỏi không hợp lệ; hãy kiểm tra trường bắt buộc, kiểu và giới hạn giá trị.");
    }

    /**
     * Chuyển xung đột phiên bản thành lỗi có thể xử lý bằng cách tải lại dữ liệu.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> stale(OptimisticLockingFailureException exception) {
        return response(ErrorCode.QUESTION_VERSION_CONFLICT, ErrorCode.QUESTION_VERSION_CONFLICT.getMessage());
    }

    /**
     * Xử lý khóa đồng thời và khóa ngoại mà không lộ tên bảng hoặc câu lệnh SQL.
     */
    @ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ApiResponse<Void>> conflict(Exception exception) {
        return response(ErrorCode.QUESTION_DATA_CONFLICT, ErrorCode.QUESTION_DATA_CONFLICT.getMessage());
    }

    /**
     * Ghi log phía máy chủ và trả thông báo an toàn với lỗi ngoài dự kiến.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        log.error("Không thể xử lý API câu hỏi", exception);
        return response(ErrorCode.UNCATEGORIZED_EXCEPTION, "Không thể xử lý yêu cầu lúc này.");
    }

    /**
     * Tạo envelope lỗi thống nhất với API hiện có.
     */
    private ResponseEntity<ApiResponse<Void>> response(ErrorCode code, String message) {
        return ResponseEntity.status(code.getHttpStatusCode())
                .body(ApiResponse.<Void>builder().code(code.getCode()).message(message).build());
    }
}
