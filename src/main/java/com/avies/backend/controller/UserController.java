package com.avies.backend.controller;

import com.avies.backend.dto.response.ApiResponse;
import com.avies.backend.dto.response.UserResponse;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.UserRepository;
import com.avies.backend.service.UserService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserController {

    UserRepository userRepository;
    private final UserService userService;

    /**
     * Lấy thông tin user hiện tại đang đăng nhập từ SecurityContext
     */
    @GetMapping("/my-info")
    public ApiResponse<UserResponse> getMyInfo() {
        var context = SecurityContextHolder.getContext();
        String username = context.getAuthentication().getName();

        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        return ApiResponse.<UserResponse>builder()
                .result(UserResponse.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .fullName(user.getFullName())
                        .role(user.getRole() != null ? user.getRole().getCode() : null)
                        .createdAt(user.getCreatedAt())
                        .build())
                .build();
    }

    /**
     * Lấy thông tin tất cả Users
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ApiResponse<List<UserResponse>> getAllUsers() {
        return ApiResponse.<List<UserResponse>>builder()
                .result(userService.getAllUsers())
                .build();
    }

    /**
     * Endpoint test quyền ADMIN
     */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @GetMapping("/admin-only")
    public ApiResponse<String> adminOnly() {
        return ApiResponse.<String>builder()
                .result("Xin chào ADMIN! Bạn có quyền truy cập endpoint này.")
                .build();
    }

    /**
     * Endpoint test quyền LECTURER hoặc ADMIN
     */
    @PreAuthorize("hasAnyAuthority('ROLE_LECTURER', 'ROLE_ADMIN')")
    @GetMapping("/lecturer-only")
    public ApiResponse<String> lecturerOnly() {
        return ApiResponse.<String>builder()
                .result("Xin chào Giảng viên! Bạn có quyền truy cập endpoint này.")
                .build();
    }

    /**
     * Endpoint test quyền STUDENT, LECTURER hoặc ADMIN
     */
    @PreAuthorize("hasAnyAuthority('ROLE_STUDENT', 'ROLE_LECTURER', 'ROLE_ADMIN')")
    @GetMapping("/student-only")
    public ApiResponse<String> studentOnly() {
        return ApiResponse.<String>builder()
                .result("Xin chào Sinh viên! Bạn có quyền truy cập endpoint này.")
                .build();
    }
}
