package com.avies.backend.service.impl;

import com.avies.backend.dto.response.UserResponse;
import com.avies.backend.repository.UserRepository;
import com.avies.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

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
}
