package com.avies.backend.service;

import com.avies.backend.dto.response.UserResponse;
import java.util.List;

public interface UserService {

    List<UserResponse> getAllUsers();
}