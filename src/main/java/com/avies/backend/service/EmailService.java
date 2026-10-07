package com.avies.backend.service;

public interface EmailService {
    void sendAccountCreatedEmail(String toEmail, String username, String password, String roleName);
}
