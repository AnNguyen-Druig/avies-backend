package com.avies.backend.exception.exceptions;

import com.avies.backend.controller.LecturerSubjectAssignmentController;
import com.avies.backend.dto.response.ApiResponse;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** Chuẩn hóa lỗi riêng cho phân công, không thay đổi hành vi các API đang có. */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = LecturerSubjectAssignmentController.class)
public class AssignmentExceptionHandler {
    /** Trả mã lỗi nghiệp vụ mà không phản chiếu thông tin nội bộ. */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> business(AppException exception) {
        return response(exception.getErrorCode(), exception.getErrorCode().getMessage());
    }

    /** Trả 403 khi người gọi không có vai trò phù hợp. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden(AccessDeniedException exception) {
        return response(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getMessage());
    }

    /** Trả 400 cho JSON, ID, danh sách môn hoặc tham số phân trang không hợp lệ. */
    @ExceptionHandler({BindException.class, ConstraintViolationException.class, HandlerMethodValidationException.class,
            TypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception exception) {
        return response(ErrorCode.INVALID_REQUEST, "Dữ liệu phân công không hợp lệ; hãy kiểm tra ID, danh sách môn và phân trang.");
    }

    /** Xung đột khóa hoặc tham chiếu trả 409 an toàn thay vì lỗi 500 kèm SQL. */
    @ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ApiResponse<Void>> conflict(Exception exception) {
        return response(ErrorCode.ASSIGNMENT_CONFLICT, ErrorCode.ASSIGNMENT_CONFLICT.getMessage());
    }

    /** Lỗi ngoài dự kiến chỉ được ghi chi tiết ở máy chủ. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        log.error("Không thể xử lý API phân công môn", exception);
        return response(ErrorCode.UNCATEGORIZED_EXCEPTION, "Không thể xử lý yêu cầu lúc này.");
    }

    /** Tạo envelope lỗi theo quy ước hiện tại của backend. */
    private ResponseEntity<ApiResponse<Void>> response(ErrorCode code, String message) {
        return ResponseEntity.status(code.getHttpStatusCode()).body(ApiResponse.<Void>builder()
                .code(code.getCode()).message(message).build());
    }
}
