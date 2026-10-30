package com.avies.backend.service.support;

import com.avies.backend.config.OralTranscriptionProperties;
import com.avies.backend.exception.*;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;

/** Spool bền trên ổ riêng của BE; không có GET public và không nhận đường dẫn từ Student. */
@Component
public class OralAudioStore {
    /** Cấu hình thư mục riêng, production nhiều instance cần volume chung. */ private final OralTranscriptionProperties settings;
    /** Định danh volume, không phải danh tính Student hoặc thông tin máy tính. */ private String storageId;
    /** Chỉ giữ cấu hình khi khởi tạo, chưa tạo/xóa file nào. */ public OralAudioStore(OralTranscriptionProperties settings) { this.settings = settings; }
    /** Gọi dưới khóa submission DB; publish atomic, không để worker đọc file đang viết dở. */
    public void save(UUID job, OralAudioPayload payload) {
        try {
            Path root = root(); Files.createDirectories(root); Path target = path(job);
            if (Files.exists(target)) {
                if (!read(job, payload.sha256()).sha256().equals(payload.sha256())) throw new AppException(ErrorCode.ORAL_AUDIO_CONFLICT);
                return;
            }
            Path temporary = Files.createTempFile(root, "pending-", ".tmp");
            try {
                Files.write(temporary, payload.bytes());
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        } catch (AppException safe) { throw safe; }
        catch (IOException failure) { throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_FAILED); }
    }
    /** Kiểm tra hash trước mỗi lần gửi Groq; không gửi dữ liệu ổ đĩa đã bị đổi. */
    public OralAudioPayload read(UUID job, String expectedHash) {
        try (var input = Files.newInputStream(path(job))) {
            var audio = OralAudioPayload.detect(input.readNBytes(OralAudioPayload.MAX_BYTES + 1));
            if (!audio.sha256().equals(expectedHash)) throw new AppException(ErrorCode.ORAL_AUDIO_CONFLICT);
            return audio;
        } catch (AppException safe) { throw safe; }
        catch (IOException missing) { throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_FAILED); }
    }
    /** Marker bền, file lock chỉ giữ vài byte để hai process dùng chung volume không tạo ID khác nhau. */
    public synchronized String storageId() {
        if (storageId != null) return storageId;
        try {
            Files.createDirectories(root());
            try (var channel = java.nio.channels.FileChannel.open(root().resolve(".storage-id"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                if (channel.size() == 0) { channel.write(java.nio.ByteBuffer.wrap(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII))); channel.force(true); }
                if (channel.size() != 36) throw new IOException("Invalid storage marker");
                var data = java.nio.ByteBuffer.allocate(36); channel.position(0); while (data.hasRemaining() && channel.read(data) != -1) { }
                storageId = UUID.fromString(new String(data.array(), java.nio.charset.StandardCharsets.US_ASCII)).toString(); return storageId;
            }
        } catch (IOException | IllegalArgumentException invalid) { throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_NOT_READY); }
    }
    /** Tên file chỉ từ UUID do BE cấp, không phụ thuộc originalFilename. */
    private Path path(UUID job) { return root().resolve(job.toString() + ".audio"); }
    /** Không đặt spool trong static/public hoặc thư mục uploads của Learning Materials. */
    private Path root() {
        Path value = Path.of(settings.getDirectory()).toAbsolutePath().normalize();
        for (Path part : value) if (java.util.Set.of("static", "public", "uploads").contains(part.toString().toLowerCase(java.util.Locale.ROOT)))
            throw new AppException(ErrorCode.ORAL_TRANSCRIPTION_NOT_READY);
        return value;
    }
}
