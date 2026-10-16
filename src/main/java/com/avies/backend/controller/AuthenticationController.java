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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.*;

import java.text.ParseException;

@Tag(name = "Authentication", description = "API xác thực: đăng nhập, đăng ký, làm mới token, kiểm tra token (introspect), đăng xuất")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthenticationController {

    AuthenticationService authenticationService;

    /**
     * API Đăng nhập
     */
    @Operation(summary = "Đăng nhập", description = "Xác thực username/password, trả về JWT Access Token + Refresh Token")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Đăng nhập thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Sai username hoặc password")
    })
    @SecurityRequirements
    @PostMapping("/signin")
    public ApiResponse<AuthenticationResponse> signIn(@RequestBody @Valid AuthenticationRequest request) {
        var result = authenticationService.authenticate(request);
        return ApiResponse.<AuthenticationResponse>builder()
                .result(result)
                .build();
    }

    /**
     * API Đăng ký tài khoản
     */
    @Operation(summary = "Đăng ký tài khoản", description = "Tạo tài khoản mới với role mặc định STUDENT")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Đăng ký thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Username đã tồn tại hoặc dữ liệu không hợp lệ")
    })
    @SecurityRequirements
    @PostMapping("/signup")
    public ApiResponse<UserResponse> signUp(@RequestBody @Valid SignUpRequest request) {
        var result = authenticationService.signup(request);
        return ApiResponse.<UserResponse>builder()
                .result(result)
                .build();
    }

    /**
     * API Làm mới Access Token
     */
    @Operation(summary = "Làm mới Access Token", description = "Dùng Refresh Token để lấy Access Token mới khi token cũ hết hạn")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token mới đã được cấp"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Refresh Token không hợp lệ hoặc đã hết hạn")
    })
    @SecurityRequirements
    @PostMapping({"/refresh"})
    public ApiResponse<AuthenticationResponse> refresh(@RequestBody @Valid RefreshRequest request)
            throws ParseException, JOSEException {
        var result = authenticationService.refreshToken(request);
        return ApiResponse.<AuthenticationResponse>builder()
                .result(result)
                .build();
    }

    /**
     * API Kiểm tra token hợp lệ
     */
    @Operation(summary = "Kiểm tra token", description = "Introspect JWT token để xác định token còn hiệu lực không")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Kết quả kiểm tra token (introspect)")
    })
    @SecurityRequirements
    @PostMapping("/introspect")
    public ApiResponse<IntrospectResponse> introspect(@RequestBody @Valid IntrospectRequest request) {
        var result = authenticationService.introspect(request);
        return ApiResponse.<IntrospectResponse>builder()
                .result(result)
                .build();
    }

    /**
     * API Đăng xuất (Blacklist token)
     */
    @Operation(summary = "Đăng xuất", description = "Vô hiệu hóa token hiện tại bằng cách đưa vào blacklist")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Đăng xuất thành công")
    })
    @SecurityRequirements
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody @Valid LogoutRequest request) {
        authenticationService.logout(request);
        return ApiResponse.<Void>builder()
                .message("Logout successful")
                .build();
    }
}