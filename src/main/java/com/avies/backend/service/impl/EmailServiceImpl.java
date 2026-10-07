package com.avies.backend.service.impl;

import com.avies.backend.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailServiceImpl implements EmailService {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Override
    public void sendAccountCreatedEmail(String toEmail, String username, String password, String roleName) {
        if (mailSender == null) {
            log.warn("JavaMailSender chưa được cấu hình. Bỏ qua việc gửi email tới {}", toEmail);
            return;
        }

        if (fromEmail == null || fromEmail.isBlank()) {
            log.warn("Chưa cấu hình spring.mail.username / MAIL_USERNAME. Bỏ qua việc gửi email tới {}", toEmail);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (fromEmail != null && !fromEmail.isBlank()) {
                message.setFrom(fromEmail);
            }
            message.setTo(toEmail);
            message.setSubject("Tài khoản AIVES của bạn đã được tạo");
            message.setText("Xin chào,\n\n"
                    + "Tài khoản của bạn đã được tạo bởi quản trị viên trên hệ thống AIVES.\n\n"
                    + "Thông tin đăng nhập của bạn:\n"
                    + "- Tên đăng nhập: " + username + "\n"
                    + "- Mật khẩu: " + password + "\n"
                    + "- Vai trò: " + roleName + "\n\n"
                    + "Vui lòng đăng nhập vào hệ thống và đổi mật khẩu ở phần Cập nhật thông tin cá nhân để bảo vệ tài khoản của bạn.\n\n"
                    + "Trân trọng,\nĐội ngũ AIVES");

            mailSender.send(message);
            log.info("Đã gửi email thông báo tạo tài khoản thành công tới {}", toEmail);
        } catch (Exception e) {
            log.error("Gửi email thông báo tạo tài khoản tới {} thất bại: {}", toEmail, e.getMessage(), e);
        }
    }
}
