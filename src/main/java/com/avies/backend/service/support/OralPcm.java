package com.avies.backend.service.support;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import com.avies.backend.exception.*;

/** PCM có định dạng cố định tại transport; WAV giữ bằng chứng theo cơ chế private audio hiện tại. */
public final class OralPcm {
    private OralPcm() { }
    public static byte[] wav(byte[] pcm, int rate) {
        if (pcm == null || pcm.length < 2 || pcm.length % 2 != 0 || pcm.length + 44 > OralAudioPayload.MAX_BYTES)
            throw new AppException(ErrorCode.ORAL_AUDIO_INVALID);
        return ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46464952).putInt(36 + pcm.length).putInt(0x45564157)
                .putInt(0x20746d66).putInt(16).putShort((short)1).putShort((short)1)
                .putInt(rate).putInt(rate * 2).putShort((short)2).putShort((short)16)
                .putInt(0x61746164).putInt(pcm.length).put(pcm).array();
    }
    public static byte[] fromWav(byte[] wav) {
        com.avies.backend.service.impl.GeminiOralSpeechProvider.validateWav(wav);
        var b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 12; offset + 8 <= wav.length;) {
            int length = b.getInt(offset + 4);
            if (b.getInt(offset) == 0x61746164) return Arrays.copyOfRange(wav, offset + 8, offset + 8 + length);
            offset += 8 + length + length % 2;
        }
        throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
    }
}
