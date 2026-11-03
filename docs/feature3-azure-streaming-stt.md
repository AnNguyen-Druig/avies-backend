# Azure Speech streaming STT — backend SDK

## Phạm vi đã triển khai

`StreamingTranscriptionService` là service nội bộ Spring dùng Azure Speech Java SDK 1.51.0. Service nhận từng frame PCM, phát `PARTIAL`/`FINAL` và trả transcript hoàn chỉnh sau EOF. Hỗ trợ `vi` → `vi-VN`, `en` → `en-US`.

Tầng SDK này hiện đã được nối với microphone FE qua WebSocket và lưu final/audio qua bộ điều phối lượt thi. Gemini TTS cũng có đường phát streaming; Groq upload vẫn giữ để phục hồi khi đã cấu hình. Xem [giao thức và cách thử streaming](feature3-streaming.md). Không cần migration database mới.

## Cấu hình cục bộ

Đặt trong `.env` backend hoặc biến môi trường; không commit key:

```properties
ORAL_AZURE_STT_ENABLED=true
AZURE_SPEECH_API_KEY=<key của Speech Resource F0>
AZURE_SPEECH_REGION=southeastasia
```

Mặc định tắt khi không khai báo flag. SDK dùng key + region để mở kết nối Speech; không dùng endpoint REST quản trị resource. Code không có thao tác tạo resource, bật billing hay chuyển F0 sang S0.

## Hợp đồng audio và transcript

- Gọi `open(language, consumer)` sau khi transport kiểm tra xác thực, chủ sở hữu bài thi, câu hiện tại và quyền điều khiển tab.
- Gọi `write(pcm)` với PCM signed 16-bit little-endian, mono 16 kHz. Mỗi frame 100 ms tương ứng 3.200 byte; tối đa 64.000 byte/frame, tổng tối đa 5 phút/phiên. Không truyền WebM/Opus/WAV trực tiếp; FE cần chuyển microphone sang PCM trước khi dùng service này.
- `PARTIAL`: thay thế bản tạm của đoạn đang nói, không nối tất cả revision và không lưu như câu trả lời đã nộp.
- `FINAL`: một đoạn Azure đã chốt, có `segmentIndex`. Chưa có nghĩa Student đã nộp toàn bộ câu trả lời.
- Consumer callback phải trả về nhanh; không block để đợi `finish()` hoặc gọi đồng bộ tới dịch vụ AI trong callback.
- `finish()` đóng đầu vào audio, đợi Azure gửi hết final và `sessionStopped`, rồi đóng SDK trước khi hoàn tất kết quả. Không gọi Stop ngay khi bấm kết thúc vì có thể cắt phần audio cuối.
- Chỉ `Result.transcript()` sau `finish()` thành công mới đủ điều kiện đưa vào bước xác minh/nộp câu trả lời của lượt thi. Caller vẫn phải kiểm tra turn/lease/idempotency trước khi lưu.
- `close()` khi hủy/mất kết nối giải phóng phiên; không tự nộp partial hoặc tạo transcript rỗng giả. Audio không có lời nói có thể trả kết quả rỗng hợp lệ; lỗi nhà cung cấp/time-out luôn là lỗi kỹ thuật.

## Giới hạn F0 và xử lý lỗi

Service chỉ cho **một phiên STT đang mở trên một instance backend**, giữ slot cho tới khi đóng native SDK xong. Phiên thứ hai bị từ chối cục bộ bằng mã `1812`, không mở thêm kết nối Azure. Nếu native cleanup thất bại, khóa giữ đóng để tránh mở chồng phiên; cần kiểm tra runtime/restart instance.

Demo chạy một instance backend và không dùng cùng resource cho một ứng dụng STT khác đồng thời. Giới hạn trong bộ nhớ này không phải khóa phân tán cho nhiều replica.

Tự hủy nếu quá 10 giây không nhận audio, quá 305 giây từ khi mở, hoặc quá 15 giây đợi final sau EOF. Khi Student im lặng nhưng microphone còn hoạt động, transport vẫn phải gửi frame PCM im lặng. Mã lỗi dùng lại: `1810` chưa sẵn sàng/xác thực; `1811` lỗi kỹ thuật; `1812` bận/quota; `1813` audio không hợp lệ. Không log key, nội dung SDK errorDetails hoặc transcript Student.

## Kiểm thử

Unit/regression, không gọi cloud:

```powershell
mvn test
```

Kiểm thử Azure thật, opt-in riêng (đọc `.env` cục bộ, không mở Spring app/Supabase):

```powershell
mvn test -Pexternal-services '-DallowExternalServices=true' '-Dtest=AzureSpeechStreamingLiveTest'
```

Live test tạo hai mẫu audio ngắn Việt/Anh bằng Azure TTS **chỉ để làm fixture**, rồi gửi từng frame 100 ms vào STT thật. Có sử dụng một lượng nhỏ quota miễn phí TTS và STT; không đổi cấu hình TTS sản phẩm. Test bắt buộc nhận partial, final, transcript chứa từ khóa của mẫu và xác minh phiên STT thứ hai bị chặn. Default `mvn test` không gọi cloud.

Kết quả chạy trên Windows/Java 21 ngày 03/11/2026, resource `southeastasia`: Việt 10 partial/3 final, chốt sau EOF 234 ms; Anh 20 partial/3 final, 219 ms. Đây là phép thử audio tổng hợp, không phải benchmark microphone/mạng thực hoặc cam kết latency toàn luồng AI + TTS.

## Bước nối transport tiếp theo

WebSocket được xác thực, frame/backpressure, kiểm tra lượt thi/quyền tab, FE capture PCM và xử lý disconnect đã được bổ sung; final chỉ được nộp qua bộ điều phối BE hiện tại. Cần đo tiếp tốc độ toàn luồng trong ca thi thực tế. Không gửi đáp án tham khảo hoặc rubric ra client.

Tài liệu SDK và giới hạn: [Azure Speech Java](https://learn.microsoft.com/en-us/java/api/com.microsoft.cognitiveservices.speech?view=azure-java-stable), [Speech quotas](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/speech-services-quotas-and-limits).
