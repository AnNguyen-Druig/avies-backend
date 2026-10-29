package com.avies.backend.exception.exceptions;

import com.avies.backend.controller.ExamSessionController;
import com.avies.backend.controller.StudentAssignmentController;
import com.avies.backend.controller.StudentExamController;
import com.avies.backend.controller.EligibleStudentController;
import com.avies.backend.controller.ExamLifecycleController;
import com.avies.backend.controller.OralExamController;
import com.avies.backend.controller.OralExamRuntimeController;
import com.avies.backend.controller.OralSpeechController;
import com.avies.backend.controller.OralTranscriptionController;
import com.avies.backend.dto.response.ApiResponse;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.exception.ExamSessionQuestionPoolException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** Chuẩn hóa lỗi API phiên thi và phân công sinh viên; không đổi exception contract của Feature 1. */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ExamSessionController.class, StudentAssignmentController.class,
        StudentExamController.class, EligibleStudentController.class, ExamLifecycleController.class, OralExamController.class, OralExamRuntimeController.class, OralSpeechController.class, OralTranscriptionController.class})
public class ExamSessionExceptionHandler {
    /** Chỉ trả thông điệp ErrorCode đã kiểm soát, không đưa dữ liệu exception nội bộ ra client. */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> business(AppException exception) {
        if (exception instanceof ExamSessionQuestionPoolException shortage) {
            ErrorCode error = ErrorCode.EXAM_SESSION_QUESTIONS_REQUIRED;
            return ResponseEntity.status(error.getHttpStatusCode()).body(ApiResponse.<Void>builder()
                    .code(error.getCode()).message(error.getMessage().formatted(shortage.getRequiredCount())).build());
        }
        return response(exception.getErrorCode());
    }

    /** Từ chối quyền thao tác tại method security bằng 403 thống nhất. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden(AccessDeniedException exception) {
        return response(ErrorCode.UNAUTHORIZED);
    }

    /** JSON sai kiểu, timestamp thiếu offset, trường ngoài DTO hoặc validation sai đều trả 400. */
    @ExceptionHandler({BindException.class, ConstraintViolationException.class, HandlerMethodValidationException.class,
            TypeMismatchException.class, HttpMessageNotReadableException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception exception) {
        return response(ErrorCode.INVALID_REQUEST);
    }

    /** Lỗi khóa, timeout hoặc FK được ánh xạ 409 mà không lộ schema và SQL. */
    @ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class,
            OptimisticLockingFailureException.class, QueryTimeoutException.class, TransactionTimedOutException.class})
    public ResponseEntity<ApiResponse<Void>> conflict(Exception exception) {
        return response(ErrorCode.EXAM_SESSION_CONFLICT);
    }

    /** Chi tiết lỗi ngoài dự kiến chỉ lưu ở server. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        log.error("Không thể xử lý API phiên thi", exception);
        return ResponseEntity.internalServerError().body(ApiResponse.<Void>builder()
                .code(ErrorCode.UNCATEGORIZED_EXCEPTION.getCode()).message("Không thể xử lý yêu cầu lúc này.").build());
    }

    /** Tạo envelope lỗi cùng HTTP status theo ErrorCode. */
    private ResponseEntity<ApiResponse<Void>> response(ErrorCode error) {
        return ResponseEntity.status(error.getHttpStatusCode()).body(ApiResponse.<Void>builder()
                .code(error.getCode()).message(error.getMessage()).build());
    }
}
