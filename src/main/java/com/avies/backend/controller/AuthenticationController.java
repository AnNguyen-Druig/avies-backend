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

@Tag(name = "Authentication", description = "API xac thuc: dang nhap, dang ky, lam moi token, introspect, dang xuat")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthenticationController {

    AuthenticationService authenticationService;

    /**
     * API Dang nhap
     */
    @Operation(summary = "Dang nhap", description = "Xac thuc username/password, tra ve JWT Access Token + Refresh Token")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Dang nhap thanh cong"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Sai username hoac password")
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
     * API Dang ky tai khoan
     */
    @Operation(summary = "Dang ky tai khoan", description = "Tao tai khoan moi voi role mac dinh STUDENT")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Dang ky thanh cong"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Username da ton tai hoac du lieu khong hop le")
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
     * API Lam moi Access Token
     */
    @Operation(summary = "Lam moi Access Token", description = "Dung Refresh Token de lay Access Token moi khi token cu het han")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token moi da duoc cap"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Refresh Token khong hop le hoac da het han")
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
     * API Kiem tra token hop le
     */
    @Operation(summary = "Kiem tra token", description = "Introspect JWT token de xac dinh token con hieu luc khong")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ket qua introspect")
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
     * API Dang xuat (Blacklist token)
     */
    @Operation(summary = "Dang xuat", description = "Vo hieu hoa token hien tai bang cach dua vao blacklist")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Dang xuat thanh cong")
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