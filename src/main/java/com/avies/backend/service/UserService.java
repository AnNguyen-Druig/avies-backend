package com.avies.backend.service;

import com.avies.backend.dto.request.UserCreateRequest;
import com.avies.backend.dto.request.UserUpdateRequest;
import com.avies.backend.dto.response.UserResponse;
import java.util.List;

public interface UserService {

    UserResponse createUser(UserCreateRequest request);

    List<UserResponse> getAllUsers();

    UserResponse getMyInfo(String username);

    UserResponse updateUser(Long id, UserUpdateRequest request);

    void deleteUser(Long id);
}