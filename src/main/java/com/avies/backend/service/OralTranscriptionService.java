package com.avies.backend.service;

import com.avies.backend.dto.request.OralAudioRequest;
import com.avies.backend.dto.response.OralExamResponse;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;

/** Upload audio chỉ gắn với submission do SUBMIT cấp; preview không ghi kết quả thi. */
public interface OralTranscriptionService {
    /** Nhận file và metadata; STT chạy nền sau khi lưu bền. */
    OralExamResponse upload(@NotNull @Positive Long examId, @NotNull UUID job, @NotNull @Valid OralAudioRequest request, @NotNull MultipartFile audio);
    /** Thử mic và STT bằng audio thật, không tạo exam/transcript giả. */
    String preview(@NotNull @Pattern(regexp="vi|en") String language, @NotNull MultipartFile audio);
}
