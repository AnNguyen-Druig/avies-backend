package com.avies.backend.exam;

import com.avies.backend.config.OralTranscriptionProperties;
import com.avies.backend.exception.AppException;
import com.avies.backend.service.support.*;
import com.avies.backend.service.impl.GroqOralTranscriptionProviderTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Spool chỉ dùng thư mục tạm do test sở hữu, không đụng audio thật. */
class OralAudioStoreTest {
    /** Thư mục riêng cho từng ca. */ @TempDir Path directory;
    /** File vẫn đọc được bởi instance mới và cùng UUID không được thay audio. */
    @Test void survivesServiceRestartAndRejectsDifferentBytes() throws Exception {
        var props=mock(OralTranscriptionProperties.class); when(props.getDirectory()).thenReturn(directory.toString());
        var store=new OralAudioStore(props); var job=UUID.randomUUID(); var payload=OralAudioPayload.detect(GroqOralTranscriptionProviderTest.audio());
        store.save(job,payload); store.save(job,payload);
        assertThat(new OralAudioStore(props).read(job,payload.sha256()).bytes()).containsExactly(payload.bytes());
        byte[] changed=payload.bytes().clone(); changed[200]=1;
        assertThatThrownBy(() -> store.save(job,OralAudioPayload.detect(changed))).isInstanceOf(AppException.class);
        try(var files=Files.list(directory)){ assertThat(files.count()).isEqualTo(1); }
    }
    /** MIME/tên file không thể biến HTML hoặc đường dẫn tùy ý thành audio hợp lệ. */
    @Test void rejectsMismatchedMimeAndUnsafeContainer() {
        assertThatThrownBy(() -> OralAudioPayload.from(new MockMultipartFile("audio","../../secret.wav","text/html",GroqOralTranscriptionProviderTest.audio()))).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> OralAudioPayload.detect(new byte[256])).isInstanceOf(AppException.class);
    }
    /** Không gửi file đã bị sửa trên ổ đĩa ra STT. */
    @Test void detectsChangedStoredBytes() throws Exception {
        var props=mock(OralTranscriptionProperties.class); when(props.getDirectory()).thenReturn(directory.toString());
        var store=new OralAudioStore(props); var job=UUID.randomUUID(); var payload=OralAudioPayload.detect(GroqOralTranscriptionProviderTest.audio()); store.save(job,payload);
        Files.write(directory.resolve(job+".audio"),new byte[256]);
        assertThatThrownBy(() -> store.read(job,payload.sha256())).isInstanceOf(AppException.class);
    }
    /** Định danh bền qua restart, không dùng cùng ID cho hai vùng file riêng. */
    @Test void storageIdentitySurvivesRestartAndSeparatesVolumes() {
        var props=mock(OralTranscriptionProperties.class); when(props.getDirectory()).thenReturn(directory.toString());
        String id=new OralAudioStore(props).storageId();
        assertThat(new OralAudioStore(props).storageId()).isEqualTo(id);
        var other=mock(OralTranscriptionProperties.class); when(other.getDirectory()).thenReturn(directory.resolve("other").toString());
        assertThat(new OralAudioStore(other).storageId()).isNotEqualTo(id);
    }
}
