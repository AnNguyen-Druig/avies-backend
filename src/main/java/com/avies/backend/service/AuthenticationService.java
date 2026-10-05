package com.avies.backend.service;

import com.avies.backend.dto.request.AuthenticationRequest;
import com.avies.backend.dto.request.IntrospectRequest;
import com.avies.backend.dto.request.LogoutRequest;
import com.avies.backend.dto.request.RefreshRequest;
import com.avies.backend.dto.request.SignUpRequest;
import com.avies.backend.dto.response.AuthenticationResponse;
import com.avies.backend.dto.response.IntrospectResponse;
import com.avies.backend.dto.response.UserResponse;
import com.nimbusds.jose.*;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;

public interface AuthenticationService {

    /**
     * Xử lý đăng ký tài khoản (/auth/signup)
     * 1. Kiểm tra username & email trùng lặp
     * 2. Gán Role (mặc định STUDENT hoặc theo yêu cầu)
     * 3. Mã hóa password bằng BCrypt
     * 4. Lưu User vào cơ sở dữ liệu
     */
    UserResponse signup(SignUpRequest request);

    /**
     * Xử lý đăng nhập (/auth/signin)
     * 1. Tìm user theo username hoặc email
     * 2. Kiểm tra mật khẩu mã hóa BCrypt
     * 3. Sinh token JWT với scope và claims người dùng
     */
    AuthenticationResponse authenticate(AuthenticationRequest request);

    /**
     * Kiểm tra tính hợp lệ của token (/auth/introspect)
     */
    IntrospectResponse introspect(IntrospectRequest request);

    /**
     * Đăng xuất (/auth/logout): Đưa token vào danh sách đen (Blacklist) theo jti
     */
    void logout(LogoutRequest request);

    /**
     * Làm mới token (/auth/refresh)
     * 1. Xác thực token cũ còn trong cửa sổ refresh
     * 2. Blacklist token cũ chống replay attack
     * 3. Sinh token mới cho user
     */
    AuthenticationResponse refreshToken(RefreshRequest request) throws ParseException, JOSEException;

    /**
     * Xác minh chữ ký JWT và kiểm tra hạn sử dụng + Blacklist
     */
    SignedJWT verifyToken(String token, boolean isRefresh) throws JOSEException, ParseException;
}
