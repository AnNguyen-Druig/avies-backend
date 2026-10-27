# Feature 2 — Exam Session CRUD và quyền giảng viên–môn

> Bổ sung 25/10/2026: tạo phiên mới yêu cầu rubric môn đầy đủ/hợp lệ; thiếu/sai trả 400/1305, "Bạn phải tạo đầy đủ Rubrics cho môn". Chỉ kiểm tra, chưa chốt snapshot lúc tạo. Các phần mô tả baseline bàn giao ban đầu bên dưới cần đọc cùng [quy tắc lifecycle hiện hành](feature2-exam-lifecycle.md).

> Bổ sung P0 Student Assignment (19/10/2026): đã triển khai API gán/thu hồi và lịch slot trong [quy tắc Student Assignment & Scheduling](feature2-student-assignment-scheduling.md). Khi có bất kỳ StudentExam, PATCH không đổi scheduledStartAt/scheduledEndAt/slotDurationMinutes (cùng instant vẫn được); lỗi 409/1405. DELETE và bảo vệ bài đã bắt đầu giữ nguyên. Các mục ghi “chưa gán/chưa chia slot” dưới đây là phạm vi lịch sử của lần bàn giao CRUD trước đó; xem tài liệu mới để biết phạm vi hiện tại.

Ngày triển khai: **19/10/2026**, nhánh `dev/An`. Chỉ backend, dừng ở CRUD phiên thi để review.

## 1. Phạm vi và baseline

- Dùng ba bảng thi và sáu cột lịch của P0.3 hiện có; không thêm migration, bảng hoặc cột, không bật Hibernate tự sửa schema.
- Theo xác nhận của Ân, Supabase đã áp dụng `20261016_feature2_exam_foundation.sql`, verifier đạt 66/66 PASS. Đợt code này không kết nối hoặc thay đổi Supabase để xác minh lại thông tin đó.
- Kiến trúc controller → service → service/impl → repository, mapping DTO tường minh, giữ JWT/backend authentication hiện có.
- POST chưa gán sinh viên/chọn đề/chốt snapshot. Tạo phiên yêu cầu rubric hợp lệ và tối thiểu `maxMainQuestions` câu APPROVED đủ điều kiện Start trong môn; thiếu câu trả 400/1306: "Bạn phải tạo đủ n số câu hỏi chính đã được APPROVED". Không cộng Follow-ups hoặc nhân số sinh viên. Quy tắc cập nhật nằm trong [lifecycle hiện hành](feature2-exam-lifecycle.md).
- OpenAPI suy ra kiểu request/response từ DTO; endpoint có `@Operation`, mã phản hồi và mô tả quyền. Mở Swagger của backend sau khi cấu hình môi trường đúng để thao tác thủ công.

## 2. API contract

Gửi `Authorization: Bearer <access_token>`; POST/PATCH dùng `Content-Type: application/json`.

| Method | Endpoint | Quyền | Thành công |
| --- | --- | --- | --- |
| POST | `/exam-sessions` | LECTURER còn phân công môn | 201, `Location: /exam-sessions/{id}`, `ApiResponse<ExamSessionResponse>` |
| GET | `/exam-sessions` | LECTURER sở hữu / ADMIN toàn bộ | 200, `ApiResponse<PageResponse<ExamSessionResponse>>` |
| GET | `/exam-sessions/{id}` | LECTURER sở hữu / ADMIN toàn bộ | 200, `ApiResponse<ExamSessionResponse>` |
| PATCH | `/exam-sessions/{id}` | LECTURER sở hữu và còn phân công | 200, phiên sau gộp PATCH |
| DELETE | `/exam-sessions/{id}` | LECTURER sở hữu và còn phân công | 200, thông báo, không có result |

POST ví dụ (thay ID bằng môn thật được phân công; thời gian phải còn trong tương lai lúc test):

```json
{
  "title": "SWD392 Midterm Viva Exam",
  "subjectId": 1,
  "scheduledStartAt": "2026-11-01T08:00:00+07:00",
  "scheduledEndAt": "2026-11-01T11:00:00+07:00",
  "slotDurationMinutes": 20,
  "maxMainQuestions": 3,
  "maxFollowUps": 2
}
```

Cả bảy trường đều bắt buộc. JSON không nhận `lecturerId`, `status`, `id`, điểm hoặc trường ngoài hợp đồng. Các trường số phải là JSON integer trong miền Java tương ứng; không tự cắt số lẻ hoặc ép chuỗi thành số. Timestamp phải là chuỗi ISO-8601 có offset/Z, không dùng timezone của máy để bổ sung phần thiếu.

Response ví dụ:

```json
{
  "code": 1000,
  "result": {
    "id": 123,
    "title": "SWD392 Midterm Viva Exam",
    "lecturerId": 11,
    "subjectId": 1,
    "subjectCode": "SWD392",
    "subjectName": "Software Architecture and Design",
    "status": "UPCOMING",
    "scheduledStartAt": "2026-11-01T08:00:00+07:00",
    "scheduledEndAt": "2026-11-01T11:00:00+07:00",
    "slotDurationMinutes": 20,
    "maxMainQuestions": 3,
    "maxFollowUps": 2,
    "createdAt": "2026-10-19T00:00:00Z"
  }
}
```

ID/thời điểm tạo chỉ là minh họa. PostgreSQL lưu TIMESTAMPTZ theo instant; response có thể biểu diễn cùng thời điểm bằng offset khác. DTO không chứa điểm, bài thi/câu trả lời sinh viên, email, password hash hoặc token.

GET danh sách hỗ trợ `page=0..1000000`, `size=1..100` (mặc định 20), `subjectId` dương và `status=UPCOMING|ONGOING|COMPLETED` tùy chọn. Thứ tự cố định ID giảm dần. Bộ lọc không có kết quả hoặc giảng viên chưa có phiên trả danh sách rỗng, không mở rộng scope bằng `lecturerId` gửi thêm.

```json
{
  "code": 1000,
  "result": {"items": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0}
}
```

PATCH ví dụ:

```json
{"title": "SWD392 Viva — Updated", "maxFollowUps": 0}
```

Chỉ sửa sáu trường `title`, `scheduledStartAt`, `scheduledEndAt`, `slotDurationMinutes`, `maxMainQuestions`, `maxFollowUps`. Trường bỏ qua giữ nguyên; PATCH rỗng hoặc null tường minh bị từ chối. Trạng thái cuối sau gộp phải đầy đủ/hợp lệ. Với phiên legacy thiếu lịch, phải gửi bổ sung lịch và duration còn thiếu trước khi sửa thành công; không tự tạo lịch giả.

DELETE không xóa bất kỳ StudentExam, câu hỏi gán, câu trả lời hoặc điểm nào. Phiên đã mất và phiên ngoài quyền đều trả cùng `404 / 1300`; gọi lại DELETE sau thành công vẫn giữ trạng thái tài nguyên đã xóa, dù response lần sau là 404. POST không có Idempotency-Key: gửi lại POST có thể tạo phiên mới. PATCH giữ nguyên giá trị không tạo thêm bản ghi.

## 3. Ma trận quyền

| Người gọi theo DB/JWT hiện hành | POST | GET danh sách/chi tiết | PATCH/DELETE |
| --- | --- | --- | --- |
| LECTURER sở hữu, còn phân công | Được trong môn được giao | Chỉ phiên sở hữu | Được nếu trạng thái và điều kiện sử dụng hợp lệ |
| LECTURER sở hữu, đã thu hồi môn | 403 / 1101 trên môn đã thu hồi | Vẫn đọc lịch sử sở hữu | 403 / 1101 |
| LECTURER khác, cùng môn | Tạo phiên của chính mình | Không xem phiên người khác, chi tiết 404 | 404 |
| ADMIN | 403 | Toàn bộ | 403 |
| STUDENT | 403 | 403 | 403 |
| Chưa xác thực/user đã mất | 401 | 401 | 401 |

Service lấy user theo subject/username trong authentication rồi đối chiếu DB role. Authority trong token phải phù hợp role DB đang dùng; token cũ mang role khác bị từ chối. Controller còn có `@PreAuthorize`. Không có đường client tự quyết định owner hoặc nâng ADMIN thành quyền ghi.

Quyền đọc phiên thi lịch sử khác với Question/Learning Material: thu hồi môn vẫn giữ GET các phiên sở hữu theo nghiệp vụ Feature 2. Không thay đổi chính sách của hai phần nội dung đã có.

## 4. Business rules và hạn chế trạng thái

- BR-01/02: một lecturer, một subject; môn tồn tại và có phân công hiện hành khi tạo.
- BR-03: backend gán UPCOMING, không nhận status từ request.
- BR-04..07: end > start, start > thời điểm kiểm tra theo instant; duration/main questions > 0; follow-ups >= 0.
- Tiêu đề được strip/chuẩn hóa Unicode NFC, không trắng, tối đa 255 ký tự Java; từ chối NUL trước khi ghi DB.
- BR-08/10/11: chỉ chủ sở hữu LECTURER còn phân công được sửa/xóa UPCOMING; ADMIN chỉ đọc.
- BR-09: mọi StudentExam đều chặn DELETE. PATCH bị chặn nếu có StudentExam khác ASSIGNED, có started_at hoặc completed_at. Dấu thời gian vẫn chặn khi status chưa đồng bộ.
- BR-12: thu hồi không xóa phiên thi lịch sử; danh sách và chi tiết vẫn kiểm tra ownership.
- BR-13: transaction ngắn, khóa và constraint giữ dữ liệu nhất quán; không có lời gọi AI trong transaction.
- BR-14/15: DTO không lộ điểm/dữ liệu sinh viên; không tự chọn câu hoặc gán sinh viên.
- Phiên UPCOMING cũ đã qua giờ bắt đầu vẫn không PATCH được nếu lịch sau gộp không còn tương lai. DELETE chỉ xét trạng thái/quyền/chưa có StudentExam, không bắt buộc lịch còn tương lai.

## 5. Lỗi

| HTTP | Code | Trường hợp |
| --- | --- | --- |
| 400 | 1002 | JSON, trường cấm, kiểu số/timestamp, validation hoặc phân trang không hợp lệ |
| 400 | 1304 | Lịch/giới hạn sau gộp không đầy đủ hoặc không hợp lệ; bắt đầu không còn tương lai |
| 401 | 1003 | Chưa xác thực hoặc user không còn tồn tại |
| 403 | 1004 | DB role/authority không phù hợp, ADMIN ghi hoặc STUDENT sử dụng |
| 403 | 1101 | Giảng viên không còn phân công môn khi ghi |
| 404 | 1016 | Môn không tồn tại |
| 404 | 1300 | Phiên không tồn tại hoặc ngoài quyền sở hữu |
| 409 | 1301 | Phiên không còn UPCOMING |
| 409 | 1302 | Có StudentExam khiến thao tác không được phép |
| 409 | 1303 | Tranh chấp khóa, timeout hoặc constraint/FK đồng thời |
| 500 | 9999 | Lỗi ngoài dự kiến, thông báo chung; chỉ server ghi chi tiết |

`ExamSessionExceptionHandler` chỉ áp dụng `ExamSessionController`. Không phản chiếu message SQL/stacktrace của exception ra client và không sửa handler Feature 1. Khi nhận 409, tải lại dữ liệu và kiểm tra trạng thái trước khi quyết định gửi lại; không retry POST tự động.

## 6. Transaction và concurrency

Thứ tự khi tạo: **user của lecturer FOR UPDATE NOWAIT → môn FOR SHARE → exists phân công → kiểm tra dữ liệu → insert phiên**.

Thứ tự PATCH/DELETE: **user FOR UPDATE NOWAIT → đọc sơ bộ phiên và kiểm tra sở hữu → môn FOR SHARE → exists phân công → phiên FOR UPDATE NOWAIT → refresh entity → kiểm tra trạng thái/StudentExam → ghi**. Role user cũng được refresh sau khi lấy khóa. Transaction ghi có timeout 10 giây.

Khóa user là cùng hàng `users` mà service phân công/thu hồi hiện có khóa, nên:

- Nếu ghi phiên lấy khóa trước, thu hồi đợi commit rồi chỉ xóa liên kết phân công; phiên đã tạo được giữ.
- Nếu thu hồi lấy khóa trước, request ghi trả 409 khi còn tranh chấp; sau thu hồi commit, lần gọi mới trả 403.
- NOWAIT cũng chặn hai transaction ghi phiên chồng nhau của cùng lecturer, kể cả trên hai phiên khác nhau. Đây là lựa chọn khóa thô phù hợp MVP; client cần xử lý 409.

Khóa phiên dùng native SQL `FOR UPDATE`, không dùng khóa yếu `FOR NO KEY UPDATE`. Nó xung đột với KEY SHARE mà FK insert StudentExam giữ. Nếu insert đã lấy khóa, DELETE nhận 409; nếu DELETE lấy khóa trước, insert phải đợi rồi thất bại FK sau khi phiên bị xóa. Vì vậy không có bài thi vừa insert rồi bị DELETE cascade mất. Phải giữ kiểm tra `existsBySessionId` dưới khóa này dù database có CASCADE. Tham khảo [PostgreSQL — Row-level locks](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS).

Giao thức cho bước StudentExam assignment/start tiếp theo: khóa hàng lecturer sở hữu trước, môn rồi parent session, sau đó mới thao tác child theo thứ tự ID ổn định và kiểm tra lại state/ownership. Không khóa child rồi quay lại parent. Nếu cần khóa thêm user sinh viên hoặc nhiều phiên, thiết kế thứ tự chung trước khi triển khai.

Giới hạn phải giữ rõ:

- Khóa bi quan bảo vệ transaction chồng nhau và gộp PATCH trên dữ liệu mới nhất; chưa có version/If-Match để phát hiện form cũ gửi sau khi transaction khác đã commit. Không tuyên bố UNIQUE thay thế kiểm soát đồng thời.
- SQL thủ công thu hồi phân công hoặc cập nhật status StudentExam mà bỏ qua giao thức khóa có thể phá giả định nghiệp vụ. FK tự bảo vệ insert/delete quan hệ, nhưng không tự kiểm tra role/phân công hoặc khóa parent khi chỉ đổi status child.
- Chưa kiểm tra slot sinh viên nằm trong cửa sổ phiên, chồng lịch hoặc sức chứa; không tự điều chỉnh lịch sinh viên khi PATCH phiên. Quy tắc rescheduling và activation cần chốt ở pha tiếp theo.

## 7. Kiểm thử

Chạy tại backend bằng JDK 21, Docker Desktop Linux engine:

```powershell
.\mvnw.cmd clean verify -Ppostgres-it -B -ntp
```

- `ExamSessionControllerTest`: HTTP contract, 201/Location, method security, JSON nghiêm ngặt, PATCH presence/null, phân trang, mã lỗi và không lộ nội bộ.
- `ExamSessionCrudPostgresIT`: controller/service/repository thật, transaction từng request; ownership, DB role/token cũ, thu hồi/phân công lại, schedule/merged PATCH/rollback, legacy, history, filters/count/pagination và service validation trực tiếp.
- `ExamSessionConcurrencyPostgresIT`: hai PATCH chồng nhau, hai thứ tự create/revoke, hai thứ tự DELETE/insert FK, state parent thay đổi và child start theo parent-lock. Dùng connection/transaction riêng và quan sát `pg_stat_activity.wait_event_type='Lock'`.
- PostgreSQL tạm được tạo từ image digest của P0.3, dùng file migration hiện có, Hibernate chỉ `validate`. Reset fixture chỉ thực hiện trong container sở hữu, không nhận URL Supabase từ cấu hình môi trường.
- Decoder JWT được giả lập ở các test mới qua Spring Security test; role, phân công, transaction và database là thật. Bộ JWT thật và Feature 1/P0.3 chạy trong hồi quy. Không dùng AI hoặc profile external-services.

Kết quả thực thi ngày **19/10/2026**, JDK 21, Maven Wrapper, PostgreSQL 17 Docker:

| Nhóm | Test đạt | Failure / Error / Skipped |
| --- | --- | --- |
| Controller/validation mới | 83 | 0 / 0 / 0 |
| CRUD/business/repository PostgreSQL mới | 25 | 0 / 0 / 0 |
| Concurrency PostgreSQL mới | 8 | 0 / 0 / 0 |
| Hồi quy Feature 1/JWT/phân công/P0.3 hiện có | 207 | 0 / 0 / 0 |
| **Tổng full build** | **323** | **0 / 0 / 0** |

Lệnh ` .\mvnw.cmd clean verify -Ppostgres-it -B -ntp ` đạt **BUILD SUCCESS**, gồm 208 test nhanh và 115 PostgreSQL, biên dịch/đóng gói thành công. Báo cáo XML nằm trong `target/surefire-reports` và `target/failsafe-reports`; log full build ở thư mục tạm máy chạy, tên `aives-exam-session-full-20261019.log`. Không commit output build.

Trong quá trình kiểm tra đã sửa cú pháp test, chuyển bộ đọc JSON mới sang Jackson 3 đúng với Spring Boot 4 và sửa test override JWT. Từng gặp `.class` do compiler IDE ghi đè với lỗi source level 21/25; lần cuối build sạch JDK 21 đạt. Thống nhất JDK 21 cho IDE/Maven và tránh IDE ghi đè output khi test chạy. Không sửa auth hoặc nâng dependency để xử lý các lỗi test đó.

Không chạy profile `external-services`, không test/deploy Supabase hoặc AI thật, không khởi động backend đang dùng database chung. Không có test bị chặn do thiếu Docker trong lần chạy cuối.

## 8. File tạo/sửa và Git

File tạo mới:

```text
docs/business_rules/feature2-exam-session-crud.md
src/main/java/com/avies/backend/controller/ExamSessionController.java
src/main/java/com/avies/backend/dto/request/ExamSessionCreationRequest.java
src/main/java/com/avies/backend/dto/request/ExamSessionUpdateRequest.java
src/main/java/com/avies/backend/dto/request/ExamSessionSearchRequest.java
src/main/java/com/avies/backend/dto/request/ExamSessionJson.java
src/main/java/com/avies/backend/dto/response/ExamSessionResponse.java
src/main/java/com/avies/backend/exception/ExamSessionExceptionHandler.java
src/main/java/com/avies/backend/service/ExamSessionService.java
src/main/java/com/avies/backend/service/impl/ExamSessionServiceImpl.java
src/test/java/com/avies/backend/exam/ExamSessionControllerTest.java
src/test/java/com/avies/backend/exam/ExamSessionCrudPostgresConfig.java
src/test/java/com/avies/backend/exam/ExamSessionCrudPostgresIT.java
src/test/java/com/avies/backend/exam/ExamSessionConcurrencyPostgresIT.java
src/test/java/com/avies/backend/exam/ExamSessionFixture.java
```

File sửa:

- `ErrorCode.java`: range 1300–1304 riêng cho phiên thi.
- `ExamSessionRepository.java`: query scope/filter/count có phân trang, fetch quan hệ đơn phục vụ DTO và khóa parent FOR UPDATE NOWAIT. Giữ nguyên các phương thức P0.3.
- `StudentExamRepository.java`: exists theo phiên và kiểm tra bài đã bắt đầu; không thêm repository trùng.
- `UserRepository.java`: biến thể NOWAIT trên cùng hàng user với khóa phân công hiện có.
- `docs/business_rules/lecturer-subject-assignments.md`: liên kết chính sách đọc lịch sử phiên thi sau thu hồi.

Nhánh `dev/An`, worktree sạch lúc bắt đầu. Lúc bàn giao: **15 file mới untracked** và **5 file đã theo dõi được sửa** như danh sách trên. `git diff --stat` cho file tracked: **48 dòng thêm, 1 dòng xóa**; chưa tính các file mới. `git diff --check` đạt. Lệnh đối chiếu: `git status --short`, `git diff --stat`, `git ls-files --others --exclude-standard`. Không tự stage, commit, push hoặc merge.

## 9. Smoke test Swagger/Postman

Chọn môi trường test và tài khoản/ID đã được nhóm cho phép thao tác; không dùng ID fixture Docker để suy ra ID Supabase. Các bước sau tạo/xóa dữ liệu khi người dùng thực hiện.

1. Run backend đã cấu hình `.env` đúng; mở `/swagger-ui/index.html`. Dùng token LECTURER được giao môn. Với Postman đặt `baseUrl=http://localhost:8080`, Authorization → Bearer Token.
2. POST `{{baseUrl}}/exam-sessions` với body ví dụ ở mục 2, điều chỉnh ID môn và ngày tương lai. Mong đợi 201, Location và status UPCOMING; lưu `result.id` thành biến `sessionId`.
3. GET `/exam-sessions?subjectId=<id>&status=UPCOMING&page=0&size=20`, rồi GET `/exam-sessions/{{sessionId}}`: 200, dữ liệu của mình. Dùng lecturer khác: chi tiết 404, danh sách không lộ phiên vừa tạo.
4. PATCH `/exam-sessions/{{sessionId}}` với `{"title":"Đã cập nhật","maxFollowUps":0}`: 200; start/end/duration/main count không đổi.
5. Gửi PATCH `{"scheduledStartAt":null}`, `{}` hoặc `{"subjectId":2}`: 400. Gửi end trước start hiện tại: 400; GET lại xác nhận không có cập nhật một phần.
6. Dùng ADMIN: GET danh sách/chi tiết 200; POST/PATCH/DELETE hợp lệ đều 403. Dùng STUDENT: cả năm API 403; bỏ token: 401.
7. Trên dữ liệu demo đã thống nhất, ADMIN thu hồi đúng cặp lecturer/môn qua API phân công. Token lecturer cũ vẫn GET được phiên sở hữu; POST/PATCH/DELETE trên môn đó trả 403/1101. Phân công lại nếu cần tiếp tục test ghi.
8. Với phiên UPCOMING không có StudentExam, DELETE trả 200; GET/DELETE lại trả 404/1300. Phiên ONGOING/COMPLETED hoặc có StudentExam được kiểm tra bằng fixture automation; không sửa trực tiếp DB dùng chung để giả trạng thái.
9. Thử `size=101`, `page=-1`, `status=CANCELLED`: 400. Với 409 thực tế, tải lại rồi kiểm tra trạng thái trước khi thao tác tiếp. Không đưa token vào ảnh/log chia sẻ.

## 10. Phần còn lại cho pha Feature 2 tiếp theo

1. Thiết kế API gán/gỡ sinh viên với role STUDENT, unique phiên/sinh viên, batch atomic và giao thức khóa mục 6.
2. Chốt rescheduling khi có sinh viên được gán; kiểm tra cửa sổ phiên, slot duration, capacity, slot_order và trùng/chồng lịch. Không suy ra lịch cho legacy.
3. Chọn câu APPROVED, chống lặp và giảm lặp giữa sinh viên; trước đó chốt snapshot/version để nội dung/rubric thi cũ được giữ nguyên.
4. Thiết kế activation/start workflow, điều kiện đủ câu/sinh viên, state transition và khóa parent trước child; chưa mở endpoint start ở đợt này.
5. Sau khi hợp đồng backend được review, tích hợp frontend và viết smoke test xuyên luồng. Optimistic edit token/idempotency POST chỉ bổ sung khi đã chốt hợp đồng/lưu trữ tương ứng.

Chờ review CRUD hiện tại trước khi triển khai các mục tiếp theo.

**EXAM SESSION CRUD COMPLETE — READY FOR REVIEW**
