package com.avies.backend.service.impl;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.AuthenticationResponse;
import com.avies.backend.dto.response.IntrospectResponse;
import com.avies.backend.dto.response.UserResponse;
import com.avies.backend.entity.InvalidatedToken;
import com.avies.backend.entity.Role;
import com.avies.backend.entity.User;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.InvalidatedTokenRepository;
import com.avies.backend.repository.RoleRepository;
import com.avies.backend.repository.UserRepository;
import com.avies.backend.service.AuthenticationService;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthenticationServiceImpl implements AuthenticationService {

    UserRepository userRepository;
    RoleRepository roleRepository;
    InvalidatedTokenRepository invalidatedTokenRepository;
    PasswordEncoder passwordEncoder;

    @NonFinal
    @Value("${jwt.signerKey}")
    protected String signerKey;

    @NonFinal
    @Value("${jwt.valid-duration}")
    protected long validDuration;

    @NonFinal
    @Value("${jwt.refreshable-duration}")
    protected long refreshableDuration;

    /**
     * Xử lý đăng ký tài khoản (/auth/signup)
     * 1. Kiểm tra username & email trùng lặp
     * 2. Gán Role (mặc định STUDENT hoặc theo yêu cầu)
     * 3. Mã hóa password bằng BCrypt
     * 4. Lưu User vào cơ sở dữ liệu
     */
    @Transactional
    @Override
    public UserResponse signup(SignUpRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new AppException(ErrorCode.USER_EXISTED);
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AppException(ErrorCode.EMAIL_EXISTED);
        }

        String targetRoleCode = (request.getRoleCode() != null && !request.getRoleCode().isBlank())
                ? request.getRoleCode().trim().toUpperCase()
                : "STUDENT";

        Role role = roleRepository.findByCode(targetRoleCode)
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_EXISTED));

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .fullName(request.getFullName())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .build();

        user = userRepository.save(user);

        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().getCode())
                .build();
    }

    /**
     * Xử lý đăng nhập (/auth/signin)
     * 1. Tìm user theo username hoặc email
     * 2. Kiểm tra mật khẩu mã hóa BCrypt
     * 3. Sinh token JWT với scope và claims người dùng
     */
    @Override
    public AuthenticationResponse authenticate(AuthenticationRequest request) {
        var user = userRepository.findByUsername(request.getUsername())
                .or(() -> userRepository.findByEmail(request.getUsername()))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

        var token = generateToken(user);

        return AuthenticationResponse.builder()
                .token(token)
                .authenticated(true)
                .role(user.getRole() != null ? user.getRole().getCode() : null)
                .username(user.getUsername())
                .fullName(user.getFullName())
                .build();
    }

    /**
     * Kiểm tra tính hợp lệ của token (/auth/introspect)
     */
    @Override
    public IntrospectResponse introspect(IntrospectRequest request) {
        var token = request.getToken();
        boolean isValid = true;
        try {
            verifyToken(token, false);
        } catch (AppException | ParseException | JOSEException e) {
            isValid = false;
        }

        return IntrospectResponse.builder()
                .valid(isValid)
                .build();
    }

    /**
     * Đăng xuất (/auth/logout): Đưa token vào danh sách đen (Blacklist) theo jti
     */
    @Override
    public void logout(LogoutRequest request) {
        try {
            var signToken = verifyToken(request.getToken(), true);

            String jti = signToken.getJWTClaimsSet().getJWTID();
            Date expiryTime = signToken.getJWTClaimsSet().getExpirationTime();

            InvalidatedToken invalidatedToken = InvalidatedToken.builder()
                    .id(jti)
                    .expiryTime(expiryTime)
                    .build();

            invalidatedTokenRepository.save(invalidatedToken);
        } catch (AppException | ParseException | JOSEException e) {
            log.info("Token already expired or invalid on logout");
        }
    }

    /**
     * Làm mới token (/auth/refresh)
     * 1. Xác thực token cũ còn trong cửa sổ refresh
     * 2. Blacklist token cũ chống replay attack
     * 3. Sinh token mới cho user
     */
    @Override
    public AuthenticationResponse refreshToken(RefreshRequest request) throws ParseException, JOSEException {
        var signJwt = verifyToken(request.getToken(), true);

        var jti = signJwt.getJWTClaimsSet().getJWTID();
        var expiryTime = signJwt.getJWTClaimsSet().getExpirationTime();

        InvalidatedToken invalidatedToken = InvalidatedToken.builder()
                .id(jti)
                .expiryTime(expiryTime)
                .build();

        invalidatedTokenRepository.save(invalidatedToken);

        var username = signJwt.getJWTClaimsSet().getSubject();
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));

        var token = generateToken(user);

        return AuthenticationResponse.builder()
                .token(token)
                .authenticated(true)
                .role(user.getRole() != null ? user.getRole().getCode() : null)
                .username(user.getUsername())
                .fullName(user.getFullName())
                .build();
    }

    /**
     * Xác minh chữ ký JWT và kiểm tra hạn sử dụng + Blacklist
     */
    @Override
    public SignedJWT verifyToken(String token, boolean isRefresh) throws JOSEException, ParseException {
        JWSVerifier verifier = new MACVerifier(signerKey.getBytes());
        SignedJWT signedJWT = SignedJWT.parse(token);

        Date expiryTime = (isRefresh)
                ? new Date(signedJWT.getJWTClaimsSet().getIssueTime().toInstant().plus(refreshableDuration, ChronoUnit.SECONDS).toEpochMilli())
                : signedJWT.getJWTClaimsSet().getExpirationTime();

        var verified = signedJWT.verify(verifier);

        if (!(verified && expiryTime != null && expiryTime.after(new Date()))) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        String jti = signedJWT.getJWTClaimsSet().getJWTID();
        if (jti != null && invalidatedTokenRepository.existsById(jti)) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        return signedJWT;
    }

    /**
     * Sinh JWT Token với thuật toán HMAC-SHA512
     */
    private String generateToken(User user) {
        JWSHeader header = new JWSHeader(JWSAlgorithm.HS512);

        String roleClaim = (user.getRole() != null && user.getRole().getCode() != null)
                ? "ROLE_" + user.getRole().getCode().trim().toUpperCase()
                : "ROLE_STUDENT";

        JWTClaimsSet jwtClaimsSet = new JWTClaimsSet.Builder()
                .subject(user.getUsername())
                .issuer("aives.com")
                .issueTime(new Date())
                .expirationTime(new Date(
                        Instant.now().plus(validDuration, ChronoUnit.SECONDS).toEpochMilli()
                ))
                .jwtID(UUID.randomUUID().toString())
                .claim("scope", roleClaim)
                .claim("userId", user.getId())
                .claim("email", user.getEmail())
                .build();

        Payload payload = new Payload(jwtClaimsSet.toJSONObject());
        JWSObject jwsObject = new JWSObject(header, payload);

        try {
            jwsObject.sign(new MACSigner(signerKey.getBytes()));
            return jwsObject.serialize();
        } catch (JOSEException e) {
            log.error("Cannot create JWT token", e);
            throw new RuntimeException("Cannot create token", e);
        }
    }
}
