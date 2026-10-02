package com.avies.backend.controller;

import com.nimbusds.jose.JOSEException;
import com.avies.backend.dto.request.AuthenticationRequest;
import com.avies.backend.dto.request.IntrospectRequest;
import com.avies.backend.dto.request.LogoutRequest;
import com.avies.backend.dto.request.RefreshRequest;
import com.avies.backend.dto.request.SignUpRequest;
import com.avies.backend.dto.response.ApiResponse;
import com.avies.backend.dto.response.AuthenticationResponse;
import com.avies.backend.dto.response.IntrospectResponse;
import com.avies.backend.dto.response.UserResponse;
import com.avies.backend.service.AuthenticationService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.*;

import java.text.ParseException;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthenticationController {

    AuthenticationService authenticationService;

    /**
     * API Đăng nhập
     * POST /auth/signin
     */
    @PostMapping("/signin")
    public ApiResponse<AuthenticationResponse> signIn(@RequestBody @Valid AuthenticationRequest request) {
        var result = authenticationService.authenticate(request);
        return ApiResponse.<AuthenticationResponse>builder()
                .result(result)
                .build();
    }

    /**
     * API Đăng ký tài khoản
     * POST /auth/signup
     */
    @PostMapping("/signup")
    public ApiResponse<UserResponse> signUp(@RequestBody @Valid SignUpRequest request) {
        var result = authenticationService.signup(request);
        return ApiResponse.<UserResponse>builder()
                .result(result)
                .build();
    }

    /**
     * API Làm mới Access Token (Hỗ trợ cả /auth/refresh và /auth/refesh)
     * POST /auth/refresh hoặc POST /auth/refesh
     */
    @PostMapping({"/refresh", "/refesh"})
    public ApiResponse<AuthenticationResponse> refresh(@RequestBody @Valid RefreshRequest request)
            throws ParseException, JOSEException {
        var result = authenticationService.refreshToken(request);
        return ApiResponse.<AuthenticationResponse>builder()
                .result(result)
                .build();
    }

    /**
     * API Kiểm tra token hợp lệ
     * POST /auth/introspect
     */
    @PostMapping("/introspect")
    public ApiResponse<IntrospectResponse> introspect(@RequestBody @Valid IntrospectRequest request) {
        var result = authenticationService.introspect(request);
        return ApiResponse.<IntrospectResponse>builder()
                .result(result)
                .build();
    }

    /**
     * API Đăng xuất (Blacklist token)
     * POST /auth/logout
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody @Valid LogoutRequest request) {
        authenticationService.logout(request);
        return ApiResponse.<Void>builder()
                .message("Logout successful")
                .build();
    }
}
