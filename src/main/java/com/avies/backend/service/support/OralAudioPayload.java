package com.avies.backend.service.support;

import com.avies.backend.exception.*;
import org.springframework.web.multipart.MultipartFile;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;

/** Audio được kiểm tra ở biên HTTP; không sử dụng tên file hoặc đường dẫn do client gửi. */
public record OralAudioPayload(byte[] bytes, String mediaType, String extension, String sha256) {
    /** Mỗi lượt tối đa 8 MiB, hỗ trợ container do MediaRecorder phổ biến tạo ra. */ public static final int MAX_BYTES = 8 * 1024 * 1024;
    /** Đọc bounded trước khi ghi ổ đĩa hoặc gửi nhà cung cấp. */
    public static OralAudioPayload from(MultipartFile file) {
        try {
            if (file == null || file.isEmpty() || file.getSize() < 128 || file.getSize() > MAX_BYTES) throw new IllegalArgumentException();
            byte[] data;
            try (var input = file.getInputStream()) { data = input.readNBytes(MAX_BYTES + 1); }
            var audio = detect(data);
            String declared = file.getContentType();
            if (declared == null || !declared.split(";")[0].equalsIgnoreCase(audio.mediaType())) throw new IllegalArgumentException();
            return audio;
        } catch (Exception invalid) { throw new AppException(ErrorCode.ORAL_AUDIO_INVALID); }
    }
    /** Container phải khớp MIME; Groq tiếp tục kiểm tra codec/nội dung và tín hiệu giọng nói. */
    public static OralAudioPayload detect(byte[] data) {
        try {
            if (data == null || data.length < 128 || data.length > MAX_BYTES) throw new IllegalArgumentException();
            String media, extension;
            if (data[0] == 0x1a && data[1] == 0x45 && (data[2] & 255) == 0xdf && (data[3] & 255) == 0xa3) { media = "audio/webm"; extension = "webm"; }
            else if (new String(data, 0, 4, StandardCharsets.US_ASCII).equals("OggS")) { media = "audio/ogg"; extension = "ogg"; }
            else if (new String(data, 4, 4, StandardCharsets.US_ASCII).equals("ftyp")) { media = "audio/mp4"; extension = "mp4"; }
            else if (new String(data, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") && new String(data, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")) { media = "audio/wav"; extension = "wav"; }
            else throw new IllegalArgumentException();
            return new OralAudioPayload(data, media, extension, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
        } catch (Exception invalid) { throw new AppException(ErrorCode.ORAL_AUDIO_INVALID); }
    }
}
