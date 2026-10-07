package com.avies.backend.service.impl;

import com.avies.backend.dto.request.UserCreateRequest;
import com.avies.backend.dto.request.UserUpdateRequest;
import com.avies.backend.dto.response.UserResponse;
import com.avies.backend.entity.Role;
import com.avies.backend.entity.User;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.RoleRepository;
import com.avies.backend.repository.UserRepository;
import com.avies.backend.service.EmailService;
import com.avies.backend.service.UserService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserServiceImpl implements UserService {

    UserRepository userRepository;
    RoleRepository roleRepository;
    PasswordEncoder passwordEncoder;
    EmailService emailService;

    @Transactional
    public UserResponse createUser(UserCreateRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new AppException(ErrorCode.USER_EXISTED);
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AppException(ErrorCode.EMAIL_EXISTED);
        }

        String targetRoleCode = request.getRoleCode() != null ? request.getRoleCode().trim().toUpperCase() : "STUDENT";
        if (!"STUDENT".equals(targetRoleCode) && !"LECTURER".equals(targetRoleCode)) {
            throw new AppException(ErrorCode.ROLE_NOT_ALLOWED, "Chỉ cho phép tạo tài khoản với vai trò STUDENT hoặc LECTURER");
        }

        Role role = roleRepository.findByCode(targetRoleCode)
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_EXISTED));

        String rawPassword = (request.getPassword() != null && !request.getPassword().isBlank())
                ? request.getPassword().trim()
                : generateRandomPassword(8);

        User user = User.builder()
                .username(request.getUsername().trim())
                .email(request.getEmail().trim())
                .fullName(request.getFullName().trim())
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .build();

        user = userRepository.save(user);

        // Gửi email thông báo cho tài khoản được tạo bởi admin kèm mật khẩu tạm thời
        emailService.sendAccountCreatedEmail(user.getEmail(), user.getUsername(), rawPassword, user.getRole().getCode());

        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().getCode())
                .createdAt(user.getCreatedAt())
                .build();
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(user -> UserResponse.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .fullName(user.getFullName())
                        .role(user.getRole() != null ? user.getRole().getCode() : null)
                        .createdAt(user.getCreatedAt())
                        .build())
                .toList();
    }

    public UserResponse getMyInfo(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole() != null ? user.getRole().getCode() : null)
                .createdAt(user.getCreatedAt())
                .build();
    }

    @Transactional
    public UserResponse updateUser(Long id, UserUpdateRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        User currentUser = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        // Xác minh user phải login đúng account của mình (kể cả admin cũng không được sửa tài khoản người khác)
        if (!currentUser.getId().equals(user.getId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Bạn chỉ có thể chỉnh sửa thông tin tài khoản của chính mình");
        }

        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            String newUsername = request.getUsername().trim();
            if (!newUsername.equals(user.getUsername()) && userRepository.existsByUsername(newUsername)) {
                throw new AppException(ErrorCode.USER_EXISTED);
            }
            user.setUsername(newUsername);
        }

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName().trim());
        }

        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            String newEmail = request.getEmail().trim();
            if (!newEmail.equals(user.getEmail()) && userRepository.existsByEmail(newEmail)) {
                throw new AppException(ErrorCode.EMAIL_EXISTED);
            }
            user.setEmail(newEmail);
        }

        // Cập nhật mật khẩu nếu có
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            if (request.getPassword().length() < 6) {
                throw new AppException(ErrorCode.PASSWORD_TOO_SHORT);
            }
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }

        // Không cho phép chỉnh sửa vai trò (role) qua API updateUser
        if (request.getRoleCode() != null && !request.getRoleCode().isBlank()) {
            String targetRoleCode = request.getRoleCode().trim().toUpperCase();
            if (user.getRole() == null || !targetRoleCode.equalsIgnoreCase(user.getRole().getCode())) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Không được phép thay đổi vai trò người dùng");
            }
        }

        user = userRepository.save(user);

        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole() != null ? user.getRole().getCode() : null)
                .createdAt(user.getCreatedAt())
                .build();
    }

    @Transactional
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new AppException(ErrorCode.USER_NOT_EXISTED);
        }
        userRepository.deleteById(id);
    }

    private String generateRandomPassword(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
