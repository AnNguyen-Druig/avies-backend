# Feature 3 — audio streaming

## Luồng đang dùng

1. FE đọc runtime. Khi `streamingSttReady=true` và browser hỗ trợ AudioWorklet, microphone gửi PCM mono 16 kHz s16le từng frame 100 ms qua WebSocket tới Azure STT. Không đợi Student nộp một file mới bắt đầu nhận dạng.
2. UI hiển thị partial/final của câu trả lời dưới nhãn **transcript tạm, chưa nộp**. Chỉ final do Azure tạo mới được backend dùng làm câu trả lời.
3. “Trả lời xong”: FE chốt frame cuối, giữ WAV và requestId trong IndexedDB, gửi FINISH. Backend thực hiện SUBMIT theo revision/lease hiện tại để dừng giờ trả lời, gửi EOF tới Azure, đợi final/sessionStopped, lưu WAV private và transcript qua transaction/claim hiện có. Cleanup SDK chạy song song sau khi transcript đã chốt; gate STT vẫn giữ đến khi native session thực sự đóng. Partial chưa chốt, lỗi mạng hoặc cancel vẫn không trở thành câu trả lời.
4. Quyết định follow-up vẫn dùng snapshot reference answer/rubric nội bộ, hạn thời gian và số lượt hiện có. Không thay đổi prompt/quy tắc AI, không gửi dữ liệu chấm ra FE.
5. Kênh WS giữ thêm tối đa 30 giây sau STT. Sau FINISH, PROGRESS có thể kèm DTO trạng thái Student đã kiểm tra lại JWT/owner/role qua cùng service GET, sau commit. FE áp dụng trực tiếp để bớt một lượt HTTP; không làm lùi revision nếu push đến trước RESULT. Các event trong lúc nhận audio được gom, không đọc lại DB cho từng revision trung gian. SSE/polling, server cũ hoặc payload WS quá lớn vẫn dùng GET dự phòng.
6. FE yêu cầu TTS cho đúng turn. Gemini được gọi với `stream:true`; PCM mono 24 kHz s16le tới FE từng chunk và được Web Audio xếp lịch phát ngay, không đợi đủ WAV. Cache đang warmup cũng cho phép nghe các chunk đã tạo rồi tiếp tục theo stream, không gọi trùng provider.
7. Chỉ sau event `end` từ BE **và** playback kết thúc, FE mới gửi PROMPT_FINISHED. Audio/network lỗi giữa chừng không mở thời gian trả lời hoặc tự chuyển câu.

## Bật và thử

Backend giữ các key/flag TTS, AI hiện tại và:

```properties
ORAL_AZURE_STT_ENABLED=true
AZURE_SPEECH_API_KEY=<key cục bộ, không commit>
AZURE_SPEECH_REGION=southeastasia
ORAL_TTS_STREAMING_ENABLED=true
AZURE_TTS_BACKUP_ENABLED=true
```

TTS streaming mặc định bật. Restart BE và reload FE sau khi cập nhật code; không cần migration mới. FE tự chọn streaming theo runtime, không cần thêm key vào FE. Groq tiếp tục phục vụ kiểm tra mic/upload dự phòng khi đã cấu hình.

Vào một lượt thi đúng lịch, nghe câu hỏi rồi bật mic. Trong lúc nói phải thấy transcript tạm thay đổi. Bấm “Trả lời xong”, kiểm tra transcript đã lưu, quyết định follow-up/câu kế tiếp và audio phát dần. Trường hợp browser không hỗ trợ AudioWorklet dùng luồng cũ.

Rollback transport bằng `VITE_ORAL_STREAMING_ENABLED=false` rồi restart Vite/build lại FE. Có thể tắt riêng TTS streaming bằng `ORAL_TTS_STREAMING_ENABLED=false` rồi restart BE. Dữ liệu/snapshot/lượt thi vẫn tương thích, không cần rollback database.

## Azure TTS dự phòng

Gemini vẫn là giọng chính. `FailoverOralSpeechProvider` tự chuyển sang Azure khi Gemini chưa phát chunk nào và trả mã 1807/1808/1809 (cấu hình/xác thực, lỗi tạo giọng, bận/quota). Không gọi Azure khi Gemini thành công. Preview WAV, nghe lại, warmup và TTS streaming dùng chung cơ chế này.

Azure dùng `AZURE_SPEECH_API_KEY`/`AZURE_SPEECH_REGION` đã cấu hình, không cần gửi hoặc tạo key khác. `AZURE_TTS_BACKUP_ENABLED` mặc định kế thừa `ORAL_AZURE_STT_ENABLED`; với cấu hình Azure STT hiện tại backup đã bật. Đặt `AZURE_TTS_BACKUP_ENABLED=false` để tắt riêng dự phòng. Có thể cấu hình `AZURE_TTS_API_KEY`/`AZURE_TTS_REGION` riêng nếu cần resource TTS khác.

Giọng mặc định cho cả `AZURE_TTS_VOICE_VI` và `AZURE_TTS_VOICE_EN` là `en-US-AvaMultilingualNeural`, hỗ trợ Việt/Anh. `AzureSpeechSsml` tạo SSML với ngôn ngữ của turn; trong câu Việt, các thuật ngữ tiếng Anh đã nhận diện như “use case”, “design pattern”, “interface” được bọc `<lang xml:lang="en-US">`. Danh sách thuật ngữ nằm trong utility này, không thêm lượt gọi LLM. Toàn bộ nội dung câu hỏi được giữ nguyên và XML-escape; snapshot/text hiển thị không bị sửa. Thuật ngữ chưa có trong danh sách vẫn theo giọng/ngôn ngữ nền và cần nghe thử trước khi bổ sung.

Có thể chọn `en-US-EmmaMultilingualNeural`, `en-US-AndrewMultilingualNeural` hoặc `en-US-BrianMultilingualNeural` bằng biến voice. Các cấu hình cũ `vi-VN-HoaiMyNeural`/`en-US-AriaNeural` vẫn hoạt động nhưng không chèn `lang`, vì chúng không phải giọng đa ngôn ngữ. Restart BE để dùng voice mới và làm mới cache audio trong bộ nhớ. Azure trả cùng PCM 24 kHz cho player; cache/replay dùng lại audio đã tạo để tiết kiệm quota.

Sau lỗi quota/xác thực Gemini, backend ưu tiên Azure trong 5 phút rồi thử lại Gemini ở yêu cầu tổng hợp mới; lỗi tạm thời dùng cooldown 30 giây. Cache audio có sẵn vẫn được dùng đến hết TTL. Nếu Gemini đã phát một phần câu hỏi rồi mới lỗi, không trộn giọng Azure vào phần còn lại: yêu cầu nghe thất bại, Student nghe lại cả câu bằng Azure trong cooldown; thời gian trả lời chưa được mở.

Azure TTS giới hạn hai tác vụ tổng hợp đồng thời trong app (tách khỏi giới hạn **một STT**). Không gọi API quản trị/billing hoặc nâng F0 sang S0. Azure cũng có quota F0; nếu cả hai dịch vụ hết hạn mức thì vẫn trả lỗi kỹ thuật an toàn. Microsoft công bố giới hạn F0 TTS [20 giao dịch/60 giây](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/speech-services-quotas-and-limits#real-time-text-to-speech).

Azure TTS dùng pool tối đa hai native session, pre-connect khi thực sự dùng backup và tái sử dụng kết nối của cùng key/region/voice. Mỗi session chỉ có một borrower; SSML, callback và buffer audio được tạo riêng từng lần. Session lỗi bị loại, session nhàn rỗi được đóng sau khoảng 5 phút và toàn bộ được dọn khi shutdown. Không tạo kết nối Azure chỉ vì ứng dụng khởi động. Gemini vẫn là provider chính.

## Giao thức

`WS /student-exams/{id}/oral-examination/stream`:

- Đầu tiên gửi `{"type":"AUTH","token":"<JWT>"}`. JWT không ở URL/subprotocol. Chỉ handshake được mở; mọi thao tác trước AUTH bị từ chối. Kết nối chưa AUTH đóng sau 5s, chưa START sau 15s.
- Sau AUTHENTICATED: `{"type":"START","request":{"clientSessionId":"...","turnId":"...","expectedRevision":0}}`. Không nhận ngôn ngữ/prompt/transcript từ FE. Server xác minh Student/owner/tab/slot/turn và lấy language từ turn.
- Sau READY: binary frames PCM 16 kHz s16le mono. Client có backpressure 128 KiB; server giới hạn 64.000 byte/frame và 8 MiB WAV. FE tự chốt mỗi bản ghi sau tối đa 250s để giữ đúng giới hạn lưu audio. Ngân sách câu/slot có thể ngắn hơn.
- Server phát `PARTIAL`/`FINAL` với `segmentIndex`, `text`; FINAL chỉ là một đoạn đã nhận dạng, chưa phải nộp bài.
- `{"type":"FINISH","command":{...SUBMIT hiện có...}}` không có transcript. Server phát RESULT chứa state hiện hành hoặc ERROR với mã lỗi an toàn.
- READY thêm `statePush:true`. PROGRESS luôn có revision và có thể thêm `state` sau FINISH: cùng DTO Student như GET, gồm tiến trình/transcript của chính Student, không chứa nội dung đề/đáp án/rubric. Kết quả chỉ gửi sau commit và kiểm tra JWT/owner/role hiện tại. Payload vượt 96 KiB chỉ gửi revision để FE GET; client/server cũ vẫn dùng đường revision. Kênh này không tự gia hạn lease; heartbeat FE vẫn có trách nhiệm đó.
- Khi WS mất, WAV/requestId vẫn giữ trong trình duyệt; đối soát rồi dùng REST/upload hiện có khi Groq sẵn sàng. Không gửi câu trả lời mới vào turn kế tiếp, không ghi partial giả hoặc coi lỗi kỹ thuật là 0 điểm.

`POST /student-exams/{id}/oral-examination/speech-stream`: cùng bearer/body như `/speech`, trả `application/x-ndjson`, `Cache-Control:no-store`, `X-Accel-Buffering:no`. Các frame `audio` chứa PCM base64; frame cuối `end` chỉ sau khi xác minh lại turn/lease/deadline. Frame `error` không được coi là kết thúc thành công. Reverse proxy cần cho Upgrade WebSocket và tắt response buffering cho endpoint này.

## Giới hạn và số đo

- Azure F0: một STT đồng thời **trên một instance backend**. Demo chạy một instance; không dùng cùng resource ở app khác đồng thời. Không có thay đổi pricing tier/S0.
- WebSocket tối đa 8 kết nối, executor/queue hữu hạn, xác minh lại quyền/slot/JWT đang nhận audio mỗi 5s; token hết hạn hoặc logout/đổi quyền đóng stream khi recheck. Không giữ transaction DB trong lúc ghi âm/gọi AI/TTS.
- Ngày 03/11/2026: loopback WS thật → Azure F0 bằng audio mẫu, Việt 11 partial/3 final, FINISH → RESULT 326 ms; Anh 16 partial/3 final, 201 ms. Bước lưu exam ở phép đo cloud này được mock; transaction thật kiểm tra riêng bằng PostgreSQL Docker.
- Gemini streaming thật: 70 chunk, 226.560 byte PCM, chunk đầu 2.661 ms, nhận xong 3.781 ms. Đây là một mẫu ngắn, không cam kết tốc độ Free Tier mọi lúc.
- Azure TTS backup thật, Gemini 429 được mô phỏng cục bộ: Việt 29 chunk, audio đầu 1.595 ms/hoàn tất 2.169 ms; Anh 29 chunk, audio đầu 896 ms/hoàn tất 1.209 ms. Hai ca này không tiêu quota Gemini.
- Azure TTS multilingual + SSML thật ngày 03/11/2026: câu Việt có “use case” nhận 32 chunk, audio đầu 1.052 ms/hoàn tất 1.422 ms; câu Anh nhận 20 chunk, audio đầu 465 ms/hoàn tất 686 ms. Đây là mẫu khác với số đo giọng cũ, không dùng để kết luận so sánh tốc độ. Test `AzureTtsBackupLiveTest` ghi WAV vào `target/tts-preview/azure-multilingual-vi.wav` và `azure-multilingual-en.wav` để nghe đánh giá phát âm.
- Ngày 05/11/2026, sau tối ưu: loopback WS → Azure STT thật nhận FINISH → RESULT 139 ms (Việt, 11 partial/3 final) và 70 ms (Anh, 16 partial/3 final). Native cleanup mất khoảng 121–123 ms và không còn chặn final. Phép thử cloud vẫn mock bước lưu exam/quyết định; PostgreSQL thật được kiểm tra riêng.
- Azure TTS pool thật ngày 05/11/2026: hai câu Việt/Anh dùng chung một native session. Câu Việt đầu tiên nhận chunk đầu 1.024 ms/hoàn tất 1.263 ms; câu Anh tái sử dụng nhận chunk đầu 203 ms/hoàn tất 423 ms. Đây là hai câu khác nhau, không phải phép so sánh trước/sau có kiểm soát.
- Gemini quyết định thật ngày 05/11/2026: payload dữ liệu tự tạo 716 ký tự, có tham chiếu câu chính và đầy đủ căn cứ nội bộ, trả follow-up hợp lệ sau khoảng 21,7 giây. Một mẫu này cho thấy provider vẫn có thể chiếm phần lớn độ trễ dù context nhỏ; các tối ưu trong ứng dụng không bảo đảm Free Tier luôn đạt dưới 5 giây.
- Chưa đo toàn luồng microphone/browser → Supabase → AI decision → loa trên ca thi của người dùng. Mục tiêu gần 5s phụ thuộc độ trễ AI, DB, mạng và quota. Streaming giảm thời gian đợi STT/upload, đợi đủ WAV và chu kỳ polling, không loại bỏ thời gian suy luận/tổng hợp giọng.

Log không chứa key/transcript/đáp án: `oral-stream ... finishMs`, `oral-ai ... providerMs`, `oral-ai-complete ... commitMs`, `oral-tts-stream ... firstAudioMs`. Dùng cùng exam/turn/job để tìm phần còn chậm khi test thực tế.

## Đo performance từ lúc nộp đến phát audio

- Trong luồng streaming, FE dev tự log `oral-latency`; build production có thể bật `VITE_ORAL_LATENCY_LOGS=true` rồi build lại. Chỉ ghi ID và thời lượng, giữ tối đa 8 trace trong RAM và bỏ trace cũ sau 2 phút. `submitToPlaybackScheduledMs` tính từ nút “Trả lời xong” đến lịch phát chunk đầu theo AudioContext (ước lượng playback, không phải đo loa vật lý); kết thúc/tạm dừng/lỗi dùng `submitToOutcomeMs`. `audioFlushedMs`, `draftSavedMs`, `finishSentMs`, `wsResultMs`, `decidingMs`, `nextStateMs`, `ttsRequestMs`, `firstAudioMs` đều là mốc tính từ lúc bấm nút.
- BE `oral-stream`: `reserveMs`, `finalWaitMs`, `audioCommitMs`; `azure-stt cleanupMs` tách thời gian dọn SDK khỏi thời gian chốt final. AI vẫn có `claimMs`, `providerMs`, `commitMs`.
- BE `oral-speech-stream`: `planMs`, `firstAudioMs`, `audioWaitMs`, `confirmMs`, `totalMs`; Azure thêm `reused` để phân biệt connection mới/cũ. Không cộng các chỉ số chồng lấn: warmup, cleanup và xử lý có thể chạy song song.
- Context AI dùng `questionRef="mainQuestion"` trong history khi prompt trùng nguyên câu chính và phần bỏ trùng lớn hơn cả hướng dẫn tham chiếu bổ sung; câu ngắn vẫn dùng payload như trước. Main question, mọi transcript và toàn bộ căn cứ snapshot vẫn được giữ, không tóm tắt/cắt ý; prompt trung lập, structured JSON, minimal thinking và kiểm tra không lộ đáp án giữ nguyên. `oral-ai-context inputChars` chỉ ghi độ dài payload.
- Chưa cam kết toàn luồng dưới 5 giây: cần đo trên ca thi/browser/Supabase thực tế, phân biệt chuyển sang câu chính có cache và follow-up vừa sinh. Hai loại này có chi phí TTS khác nhau.

## Kiểm thử

```powershell
# Backend + PostgreSQL Docker riêng, không Supabase/cloud
mvn verify -Ppostgres-it '-Dit.test=OralExamPostgresIT'

# Gọi Azure/Gemini thật, đọc key .env cục bộ, có dùng quota nhỏ
mvn test -Pexternal-services '-DallowExternalServices=true' '-Dtest=OralStreamingSocketLiveTest,GeminiSpeechStreamingLiveTest'

# Kiểm thử Azure TTS backup thật với Gemini 429 giả lập cục bộ
mvn test -Pexternal-services '-DallowExternalServices=true' '-Dtest=AzureTtsBackupLiveTest'

# Kiểm tra pool Azure TTS thật: Việt/Anh dùng chung một native connection
mvn test -Pexternal-services '-DallowExternalServices=true' '-Dtest=AzureTtsReuseLiveTest'

# Kiểm tra Gemini quyết định thật với context tự tạo có tham chiếu câu chính
mvn test -Pexternal-services '-DallowExternalServices=true' '-Dtest=GeminiDecisionContextLiveTest'

# Trong frontend
node --test
npm.cmd run build
```

Nguồn giao thức: [Gemini TTS streaming](https://ai.google.dev/gemini-api/docs/speech-generation#streaming), [Interactions event types](https://ai.google.dev/gemini-api/docs/streaming), [Spring WebSocket](https://docs.spring.io/spring-framework/reference/web/websocket/server.html).
