# Feature 2 — Start, Finish và Close

Quy tắc được Ân xác nhận ngày 25/10/2026. Không triển khai lưu câu trả lời thay Feature 3 hoặc chấm điểm thay Feature 4.

## Điều kiện tạo New Exam

POST `/exam-sessions` kiểm tra rubric của chính môn được chọn trước khi insert phiên. Cần có rubric và ít nhất một tiêu chí, nội dung hợp lệ, điểm dương/tối đa hai số thập phân/tổng đúng 10, cờ mandatory có giá trị và thứ tự dương không trùng. Dùng cùng validator/khóa rubric-criteria với Start; thiếu/sai trả HTTP 400/code 1305: **Bạn phải tạo đầy đủ Rubrics cho môn**. FE đọc rubric để chặn trước POST, BE vẫn kiểm tra lại dưới khóa nếu dữ liệu thay đổi giữa hai request. Lỗi mạng/quyền không bị diễn giải thành thiếu rubric.

Việc kiểm tra không ghi snapshot, sửa rubric hoặc backfill dữ liệu; rubric vẫn chốt ở lần Start đầu tiên thành công. Phiên cũ không tự bị xóa/sửa. Start vẫn kiểm tra để phòng nguồn bị thay đổi/xóa sau khi tạo phiên. Không cần migration cho điều kiện tạo này.

Tạo phiên mới còn yêu cầu ít nhất `maxMainQuestions` câu chính đủ điều kiện Start của chính môn đó: APPROVED, chưa lưu trữ, nội dung/Bloom/version/giới hạn trả lời hợp lệ. Thiếu trả HTTP 400/code 1306: **Bạn phải tạo đủ n số câu hỏi chính đã được APPROVED**, với `n = maxMainQuestions`; không cộng Follow-ups hoặc nhân số sinh viên. Dùng validator Start hiện có và giữ khóa đủ câu đến khi tạo xong; chưa chọn bộ câu, ghi snapshot hay thay quy tắc chống trùng. FE hiển thị lỗi qua popup hiện có. Start vẫn kiểm tra lại vì ngân hàng có thể thay đổi sau khi tạo phiên. Không tự sửa các phiên cũ; không cần migration.

Từ 26/10/2026, PATCH Edit khi `maxMainQuestions` thực sự thay đổi cũng kiểm tra lại rubric và đủ số câu hợp lệ theo giá trị mới, với cùng khóa/lỗi như New Exam. Tăng hoặc giảm đều kiểm tra; lỗi rollback toàn bộ PATCH, kể cả tên/lịch/cấu hình đi kèm. Không đổi Main (bỏ qua trường hoặc gửi lại giá trị cũ) thì không kiểm tra lại ngân hàng/rubric, giữ hành vi sửa tên/lịch/Follow-ups hiện có. Quyền, lịch khóa và cấm đổi cấu hình sau chốt snapshot/bắt đầu thi vẫn được giữ nguyên.

FE có trang **Lecturer → Rubrics** (`/lecturer/rubrics`), ngay dưới Learning Materials: chọn môn được phân công để xem/tạo/sửa/xóa rubric cấp môn, với tổng điểm tiêu chí bằng 10. Trang dùng POST `/rubrics`, GET `/rubrics/subject/{subjectId}`, PUT `/rubrics/{id}`, DELETE `/rubrics/{id}`; backend kiểm tra lại phân công trên mỗi thao tác. Cột rubric trong Question Bank cũ không phải màn hình quản lý rubric cấp môn.

Xác minh bổ sung 25/10/2026: full regression BE 628 test PASS (326 Surefire + 302 PostgreSQL). Sau đó nhóm CRUD chạy lại với hai test cạnh tranh rubric/criteria mới: 48/48 PASS; không failure/error/skipped. FE 143/143 test PASS, build thành công, lint không lỗi (còn cảnh báo hiện có). Chỉ dùng database test Docker, không áp dụng hoặc đọc/ghi Supabase. Điều kiện New Exam không cần migration mới.

## Database cần cập nhật

Chạy **chỉ file** `docs/migrations/20261025_feature2_exam_lifecycle.sql` trong **Supabase Dashboard → SQL Editor → New query → Run** trước khi test bản code mới trên database dùng chung.

Migration chỉ mở rộng CHECK trạng thái `student_exams` để nhận `ABSENT`, `EXPIRED`; không cập nhật bản ghi, xóa dữ liệu hoặc backfill. Có thể chạy lại. Không chạy lại migration chuyển rubric cũ có TRUNCATE. Agent chỉ chạy migration trong container test, chưa áp dụng Supabase.

## API

Bearer token như các API hiện có. Ba lệnh dùng POST, không gửi body hoặc gửi `{}`. Mọi field khác (answers, finalScore, status, startedAt…) đều bị từ chối HTTP 400/1002.

| API | Quyền | Kết quả |
|---|---|---|
| `/student-exams/{id}/starts` | STUDENT sở hữu lượt | `ApiResponse<StartedExamResponse>`: trạng thái/thời điểm thực tế và câu từ snapshot |
| `/student-exams/{id}/completions` | STUDENT sở hữu lượt | `ApiResponse<ExamAttemptResponse>`: trạng thái và thời điểm hoàn thành |
| `/exam-sessions/{id}/closures` | LECTURER chủ phiên còn phân công | `ApiResponse<ClosedExamSessionResponse>`: số hoàn thành/vắng/hết giờ |

### Start

- Giờ máy chủ nằm trong `[scheduled_start_at, scheduled_end_at)` của **slot sinh viên**, không chỉ cửa sổ cả phiên. Lịch phải khớp lưới slot và nằm trong phiên.
- Lượt ASSIGNED chưa có bằng chứng thực hiện; phiên UPCOMING/ONGOING; giảng viên sở hữu hiện còn vai trò/phân công môn.
- Chọn câu và chốt snapshot cùng transaction chuyển lượt sang IN_PROGRESS. Sinh viên đầu tiên bắt đầu thành công chuyển phiên sang ONGOING.
- Snapshot lỗi/không đủ câu: rollback tất cả; không để đề mồ côi hoặc phiên ONGOING giả.
- Start lại khi IN_PROGRESS trong slot trả cùng đề/startedAt/deadline, không bốc lại hoặc gia hạn. Quyền sinh viên luôn kiểm tra lại. Sau khi đã bắt đầu, thu hồi phân công giảng viên không làm mất bài đang làm của sinh viên.
- Response chỉ có nội dung/thứ tự/ID liên kết/giới hạn câu. Không lộ reference_answer, rubric hoặc điểm.

### Finish — cổng tích hợp Feature 3

- Lượt IN_PROGRESS, phiên ONGOING, đang trong slot; không nhận câu trả lời hoặc điểm trong request.
- Kiểm tra đủ snapshot và **mỗi câu chính có ít nhất một dòng `transcripts.turn_type='STUDENT_ANS'`, nội dung không trắng**, `created_at` nằm từ startedAt đến thời điểm kiểm tra trên máy chủ. Câu trả lời AI, câu của lượt khác hoặc thời điểm cũ/tương lai không được tính.
- Thiếu bằng chứng: HTTP 409/1703. Chưa có Feature 3 lưu bài thì đây là kết quả đúng, không phải lỗi cần bypass.
- Thành công chỉ ghi COMPLETED/completed_at; giữ nguyên audio/transcripts/snapshot/điểm. Chưa thực hiện AI grading hoặc chốt điểm.
- Gọi lại lượt COMPLETED trả kết quả hiện có, không đổi completed_at, kể cả sau khi phiên đã đóng. Không dùng Finish để mở lại ABSENT/EXPIRED.
- Đến đúng/cuối slot hoặc muộn hơn: không nhận Finish mới; không tự đánh dấu COMPLETED. Close sau đó phân loại EXPIRED.

**Hợp đồng dành cho thành viên làm Feature 3:** lưu transcript đã xác thực vào bảng hiện có theo `studentExamQuestionId` từ Start; lấy `created_at` từ máy chủ. Writer phải giữ cùng transaction và giao thức khóa owner → subject → session → student → StudentExam → StudentExamQuestion → transcript, kiểm tra lại IN_PROGRESS/ONGOING và thời gian slot trước khi ghi. Không được ghi tiếp sau COMPLETED/ABSENT/EXPIRED hoặc phiên đã đóng. Có thể dùng `ExamAccessPolicy.lockForInternalExamOperation` để giữ parent, nhưng helper không thay thế việc kiểm tra JWT/ownership. Không dùng SQL thẳng hoặc transaction riêng bỏ qua parent khi tích hợp. Điều kiện trên là bằng chứng lưu tối thiểu, không phải tuyên bố đã hoàn tất STT, mọi follow-up hoặc chấm bài.

### Close

- Chỉ sau/đúng giờ kết thúc cả phiên, không tự chạy theo đồng hồ, không đóng sớm dù một số bài đã xong.
- COMPLETED giữ nguyên; ASSIGNED chưa bắt đầu → ABSENT; IN_PROGRESS chưa hoàn thành → EXPIRED.
- Không xóa câu, snapshot, transcript/audio; không đặt completed_at hoặc điểm giả cho ABSENT/EXPIRED.
- Dữ liệu ASSIGNED có timestamp/điểm/audio/câu trả lời đã lưu hoặc IN_PROGRESS thiếu startedAt bị chặn 409/1704, rollback toàn bộ, không tự sửa lịch sử.
- Phiên → COMPLETED. Gọi lại hợp lệ trả cùng tổng kết. Phiên không ai bắt đầu vẫn có thể đóng UPCOMING→COMPLETED với sinh viên vắng.
- Một bài hết slot nhưng giảng viên chưa đóng phiên vẫn có thể hiển thị IN_PROGRESS; máy chủ đã chặn Start/Finish mới ngoài giờ. EXPIRED được ghi khi Close, không có scheduler tự đóng trong phạm vi này.

## Khóa, lỗi và kiểm thử

Lệnh sinh viên: kiểm tra scope trước, khóa owner → subject → session → student (SHARE NOWAIT) → child (UPDATE NOWAIT). Selection tham gia transaction hiện có; Finish khóa bộ câu và transcript. Close khóa parent rồi toàn bộ child theo ID. Các command chồng transaction có thể trả 409; không retry tự động thao tác ghi. Chỉ SQLSTATE khóa/ràng buộc đã xác định được chuyển 409, lỗi khác vẫn 500 an toàn.

Lỗi mới 1700: sai trạng thái; 1701: lịch sai/legacy; 1702: ngoài slot; 1703: thiếu câu trả lời; 1704: trạng thái/bằng chứng không nhất quán; 1705: chưa hết phiên. Lỗi quyền/không tìm thấy/xung đột dùng contract hiện có. FE hiển thị thông báo tiếng Việt.

Chạy `./mvnw.cmd clean verify -Ppostgres-it -B -ntp` với JDK 21 và Docker Linux. Test chỉ dùng PostgreSQL tạm, không đọc secrets hoặc gọi AI. `ExamLifecyclePostgresIT` kiểm tra HTTP/role/ownership, biên giờ, late start, retry, snapshot rollback, Feature 3 evidence, Close, migration lặp và khóa đồng thời. Insert transcript trong fixture chỉ là dữ liệu test, không phải API lưu bài hoặc bằng chứng Feature 3 đã triển khai.

Kết quả xác minh ngày 25/10/2026, 13:42:33 +07:00: **BUILD SUCCESS, 614 test backend PASS** (326 Surefire + 288 PostgreSQL Failsafe), gồm 53 test lifecycle; không failure/error/skipped. FE: 120 test contract/render PASS, build thành công; lint không có lỗi, còn cảnh báo sẵn có. Kết nối trình duyệt kiểm thử không khả dụng nên chưa xác nhận thao tác browser trực tiếp. Verifier nền móng chỉ đọc đã đồng bộ để nhận đúng hai bộ trạng thái cũ/mới; migration lịch sử được giữ nguyên và không chạy ngược sau lifecycle.

## Test trên browser

1. Sau migration trên database dùng chung do người phụ trách tự thực hiện, restart BE và chạy FE.
2. Tạo phiên có lịch gần thời điểm test, phân công sinh viên; đăng nhập STUDENT ở cửa sổ riêng.
3. Trong đúng slot: **Bắt đầu thi** → xem câu snapshot, trạng thái IN_PROGRESS/ONGOING. Đóng modal → **Tiếp tục bài thi**: đề/giờ bắt đầu giữ nguyên.
4. **Hoàn thành bài thi** khi Feature 3 chưa lưu đủ câu trả lời: lỗi tiếng Việt 1703. Sau khi tích hợp Feature 3 lưu đủ và còn trong slot, Finish thành công; không nhập transcript/điểm giả từ FE.
5. Sau hạn cả phiên: LECTURER chọn **Đóng phiên**, xác nhận phân loại vắng/hết giờ. Refresh lịch STUDENT để xem ABSENT/EXPIRED/COMPLETED.
