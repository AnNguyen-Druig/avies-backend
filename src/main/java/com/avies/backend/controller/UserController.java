package com.avies.backend.controller;

import com.avies.backend.dto.request.UserCreateRequest;
import com.avies.backend.dto.request.UserUpdateRequest;
import com.avies.backend.dto.response.ApiResponse;
import com.avies.backend.dto.response.UserResponse;
import com.avies.backend.service.UserService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserController {

    UserService userService;

    /**
     * Lấy thông tin user hiện tại đang đăng nhập từ SecurityContext
     */
    @GetMapping("/myInfo")
    public ApiResponse<UserResponse> getMyInfo() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return ApiResponse.<UserResponse>builder()
                .result(userService.getMyInfo(username))
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
     * Tạo tài khoản mới bởi ADMIN (chỉ cho phép role STUDENT hoặc LECTURER)
     */
    @PostMapping("/create")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ApiResponse<UserResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
        return ApiResponse.<UserResponse>builder()
                .result(userService.createUser(request))
                .build();
    }

    /**
     * Update thông tin User
     */
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<UserResponse> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return ApiResponse.<UserResponse>builder()
                .result(userService.updateUser(id, request))
                .build();
    }

    /**
     * Xóa User
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ApiResponse<String> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ApiResponse.<String>builder()
                .result("Xóa người dùng thành công")
                .build();
    }
}
