package com.avies.backend.question;

import com.avies.backend.config.CustomJwtDecoder;
import com.avies.backend.dto.response.IntrospectResponse;
import com.avies.backend.service.AuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Kiểm tra hồi quy việc phân loại lỗi JWT, không truy cập database hoặc dịch vụ bên ngoài. */
class CustomJwtDecoderTest {
    /** Dịch vụ giả lập chỉ để phân biệt kết quả token không hợp lệ với lỗi hạ tầng. */
    private final AuthenticationService authenticationService = mock(AuthenticationService.class);

    /** Token bị introspect từ chối phải được phân loại để Spring Security trả HTTP 401. */
    @Test
    void invalidTokenIsBadJwtRatherThanServiceFailure() {
        when(authenticationService.introspect(any())).thenReturn(IntrospectResponse.builder().valid(false).build());
        assertThatThrownBy(() -> decoder().decode("invalid-token")).isExactlyInstanceOf(BadJwtException.class);
    }

    /** Lỗi hạ tầng phải giữ nguyên phân loại lỗi dịch vụ, không bị đổi thành lỗi token của người dùng. */
    @Test
    void introspectionFailureRemainsServiceFailure() {
        when(authenticationService.introspect(any())).thenThrow(new IllegalStateException("Test database unavailable"));
        assertThatThrownBy(() -> decoder().decode("test-token")).isExactlyInstanceOf(JwtException.class);
    }

    /** Dựng decoder thật với duy nhất phụ thuộc introspect được thay thế, không tải cấu hình ứng dụng. */
    private CustomJwtDecoder decoder() {
        CustomJwtDecoder decoder = new CustomJwtDecoder();
        ReflectionTestUtils.setField(decoder, "authenticationService", authenticationService);
        return decoder;
    }
}
