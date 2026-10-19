# Bộ Postman kiểm thử Exam Session CRUD

Đối chiếu source backend ngày 2026-10-19. Đây là bộ request và assertion để chạy thủ công trên Postman Desktop; việc kiểm tra cấu trúc file không thay thế kết quả gọi API thực tế.

## 1. Phạm vi và an toàn dữ liệu

- File import: `Exam-Session-CRUD.postman_collection.json` (Collection v2.1).
- Tổng cộng 27 request: 23 request chính và 4 request phân quyền tùy chọn.
- Các request thực hiện ghi/xóa trên database mà backend đang kết nối. Nếu backend kết nối Supabase chung, dữ liệu test cũng nằm trên Supabase chung.
- Collection không tự chạy migration, tạo user, phân công/thu hồi môn, gán StudentExam hay thay đổi trạng thái phiên.
- Chỉ dùng tài khoản test và không gán sinh viên vào phiên do collection tạo.
- Không thay `fixtureSessionId` bằng ID dữ liệu nghiệp vụ. Request 02 ghi nhận ID phiên mới; request 25 xóa phiên này.
- Không chạy hai lượt collection song song vì chúng dùng chung biến fixture.
- Không gửi lại request 02 khi đang test dở: POST tạo mới không có idempotency key. Mỗi lần gửi thành công tạo một phiên khác, không tự xóa phiên của lượt trước.
- Nếu dừng giữa chừng, ghi lại ID test. Có thể chạy request 25 để dọn phiên đó nếu vẫn còn phân công và phiên chưa sử dụng.
- Không export/commit mật khẩu hoặc token thật. Các biến token trong file gốc đều trống.

## 2. Chuẩn bị trong IntelliJ và Postman

1. Run backend; đợi log `Started AviesBackendApplication`, không còn `APPLICATION FAILED TO START`.
2. Mở Postman Desktop, chọn **Import → Files**, chọn file JSON cùng thư mục tài liệu này.
3. Dùng tài khoản LECTURER đã được admin phân công ít nhất một môn. Không dùng tài khoản ADMIN cho luồng tạo/sửa/xóa.
4. Tạo request đăng nhập riêng, chọn **No Auth**:

```http
POST http://localhost:8080/auth/signin
Content-Type: application/json
```

```json
{
  "username": "<username giảng viên test>",
  "password": "<mật khẩu>"
}
```

5. Nếu đăng nhập thành công, copy **result.token**.
6. Mở collection **AIVES - Exam Session CRUD → Variables**, điền các biến phía dưới. Chỉ nhập chuỗi JWT, không thêm tiền tố `Bearer `.
7. Chọn **No environment** để tránh biến Environment ghi đè biến collection. Không dùng data file có các tên biến trùng.
8. Dùng Postman Desktop có hỗ trợ `pm.execution.skipRequest()`. Script dùng API này để không gửi request khi thiếu token hoặc chưa tạo được fixture. Request bị skip không được coi là PASS. Tham khảo: [Postman execution API](https://learning.postman.com/v11/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-execution).

| Biến | Giá trị cần nhập |
| --- | --- |
| baseUrl | `http://localhost:8080`, không thêm dấu / cuối |
| lecturerToken | JWT hợp lệ của giảng viên test; bắt buộc |
| subjectId | Để trống sẽ chọn môn đầu tiên từ request 01; hoặc nhập ID môn thực sự được phân công |
| adminToken | JWT ADMIN, dùng cho request 21–22; tùy chọn |
| otherLecturerToken | JWT của LECTURER khác chủ sở hữu, dùng cho request 23; tùy chọn |
| studentToken | JWT STUDENT, dùng cho request 24; tùy chọn |
| fixtureSessionId, fixtureLecturerId, fixtureTitle, fixtureStart, fixtureEnd | Để trống khi import, script tự quản lý |

Nếu có hơn 100 môn được phân công và cần chọn môn ngoài trang đầu, tự nhập subjectId đúng. Collection không cần tải toàn bộ danh sách môn để kiểm tra CRUD.

## 3. Chạy luồng chính

Chọn collection → **Run collection**, chạy 1 iteration, đúng thứ tự thư mục **A → B → C → D**, không chạy song song.

### A — Tạo, đọc, sửa

| Số | Request | Kết quả mong đợi |
| --- | --- | --- |
| 01 | GET /lecturer-subject-assignments/my-subjects?page=0&size=100 | 200, code 1000, result.items; chọn được môn hợp lệ |
| 02 | POST /exam-sessions | 201, code 1000, Location: /exam-sessions/{id}, status UPCOMING |
| 03 | GET /exam-sessions/{id} | 200, đúng ID và chủ sở hữu |
| 04 | GET /exam-sessions?page=0&size=20&subjectId=...&status=UPCOMING | 200, result.items, phân trang, chỉ phiên của lecturer; ID giảm dần |
| 05 | PATCH /exam-sessions/{id} | 200; đổi title và maxFollowUps=0; giữ các trường không gửi |
| 06 | PATCH giống hệt request 05 | 200; giữ nguyên ID và giá trị, không báo lỗi chỉ vì gửi lại |
| 07 | GET /exam-sessions/{id} | 200; đọc lại thấy dữ liệu cập nhật đã lưu |

Request 02 tự sinh tiêu đề có dấu nhận biết POSTMAN và lịch bắt đầu sau 7 ngày, kết thúc sau bắt đầu 3 giờ. Timestamp dùng ISO-8601 có Z, hợp lệ tương đương timestamp có +07:00.

Body tương đương để dùng trong Swagger (thay lịch bằng thời điểm tương lai tại lúc test):

```json
{
  "title": "Kiểm thử Exam Session CRUD",
  "subjectId": 1,
  "scheduledStartAt": "2026-11-01T08:00:00+07:00",
  "scheduledEndAt": "2026-11-01T11:00:00+07:00",
  "slotDurationMinutes": 20,
  "maxMainQuestions": 3,
  "maxFollowUps": 2
}
```

subjectId=1 chỉ là ví dụ, không bảo đảm đã được phân công. POST không nhận lecturerId, status hoặc các field ngoài DTO. Không dùng tên snake_case như subject_id.

### B — Validation và xác thực

| Số | Tình huống | HTTP / code mong đợi |
| --- | --- | --- |
| 08 | PATCH {} | 400 / 1002 |
| 09 | PATCH title=null | 400 / 1002 |
| 10 | PATCH status | 400 / 1002 |
| 11 | PATCH subjectId | 400 / 1002 |
| 12 | PATCH slotDurationMinutes="20" dạng chuỗi | 400 / 1002 |
| 13 | PATCH maxFollowUps=-1 | 400 / 1002 |
| 14 | PATCH bắt đầu trong quá khứ | 400 / 1304 |
| 15 | PATCH kết thúc bằng bắt đầu | 400 / 1304 |
| 16 | Timestamp thiếu Z/offset | 400 / 1002 |
| 17 | size=101 | 400 / 1002 |
| 18 | GET ID=0 | 400 / 1002 |
| 19 | GET danh sách không có Authorization | 401 / 1003 |
| 20 | GET sau các PATCH lỗi | 200; dữ liệu vẫn bằng kết quả PATCH hợp lệ |

maxMainQuestions và slotDurationMinutes phải là số nguyên dương; maxFollowUps là số nguyên không âm; title không trắng và tối đa 255 ký tự. Khi PATCH lịch, backend kiểm tra trạng thái sau khi gộp với dữ liệu cũ, không chỉ riêng field gửi lên.

### C — Phân quyền tùy chọn

| Số | Token và thao tác | HTTP / code mong đợi |
| --- | --- | --- |
| 21 | ADMIN GET danh sách | 200 / 1000 |
| 22 | ADMIN PATCH phiên fixture | 403 / 1004 |
| 23 | LECTURER khác GET phiên fixture | 404 / 1300; không tiết lộ phiên ngoài quyền |
| 24 | STUDENT GET danh sách | 403 / 1004 |

Nếu thiếu token tương ứng, script in SKIPPED trong Postman Console và không gửi request. Muốn xác nhận phân quyền đầy đủ cho nhóm này phải cung cấp đủ token còn hạn, đúng vai trò hiện hành. Không thể kết luận nhóm này đạt chỉ vì chúng được skip.

### D — Xóa

| Số | Request | HTTP / code mong đợi |
| --- | --- | --- |
| 25 | DELETE phiên fixture chưa sử dụng | 200 / 1000; message: Đã xóa phiên thi chưa sử dụng. |
| 26 | GET lại phiên vừa xóa | 404 / 1300 |
| 27 | DELETE lại cùng ID | 404 / 1300, không phải 500 |

DELETE trả 200 có envelope, không phải 204. Gửi lại DELETE không khôi phục bản ghi; lần hai trả 404 là hợp đồng hiện tại.

## 4. Các kiểm tra nghiệp vụ cần fixture riêng

Các tình huống sau KHÔNG tự động chạy trong collection. Dùng tài khoản và fixture test riêng, không sửa dữ liệu thật chỉ để ép trạng thái.

| Tình huống | Kết quả mong đợi |
| --- | --- |
| POST đầy đủ, subjectId không tồn tại | 404 / 1016 |
| POST đầy đủ, môn tồn tại nhưng chưa phân công lecturer | 403 / 1101 |
| Phiên của lecturer sau khi môn bị thu hồi: GET list/detail | Vẫn 200 để xem lịch sử sở hữu |
| Phiên của lecturer sau khi môn bị thu hồi: PATCH/DELETE | 403 / 1101 |
| PATCH/DELETE phiên có status ONGOING hoặc COMPLETED | 409 / 1301 (với chủ sở hữu còn phân công) |
| DELETE phiên UPCOMING đã có bất kỳ StudentExam | 409 / 1302 |
| PATCH phiên UPCOMING có StudentExam đã bắt đầu/kết thúc | 409 / 1302 |
| PATCH phiên UPCOMING chỉ có StudentExam ASSIGNED, chưa startedAt/completedAt | Có thể 200 nếu request và lịch hợp lệ |
| ADMIN GET chi tiết phiên bất kỳ | 200 nếu phiên tồn tại |
| ADMIN POST/PATCH/DELETE với body hợp lệ nếu có | 403 / 1004 |
| LECTURER khác PATCH/DELETE phiên fixture | 404 / 1300 nếu tài khoản đó hợp lệ |
| Gặp xung đột khóa / timeout / ràng buộc DB được handler ánh xạ | 409 / 1303; không lộ SQL |

Hiện CRUD này không cung cấp API đổi status hoặc API gán StudentExam. Không tự chạy SQL sửa database dùng chung để dựng các fixture nói trên. Các tình huống khóa đồng thời cần kiểm thử có điều phối, không bảo đảm tái hiện bằng cách bấm Send liên tiếp.

## 5. Dùng Swagger thay Postman

1. Mở http://localhost:8080/swagger-ui/index.html.
2. Chạy POST /auth/signin với tài khoản LECTURER test.
3. Chọn Authorize, nhập JWT theo scheme hiển thị. Với scheme HTTP bearer chỉ nhập JWT; Swagger tự thêm Bearer.
4. Mở tag **Exam sessions**, chọn **Try it out**.
5. Chạy POST → lưu result.id → GET list/detail → PATCH → GET xác nhận → DELETE → GET/DELETE kiểm tra 404.
6. Dùng body, tham số và bảng HTTP/code trong tài liệu này. Với Swagger, tự điền lịch tương lai và ID fixture, không nhập các dấu {{...}} của Postman.

Response danh sách là `result.items`, không phải mảng trực tiếp trong `result`. ADMIN chỉ đọc; các thao tác tạo/sửa/xóa thuộc LECTURER sở hữu và còn phân công.

## 6. Đọc kết quả và giới hạn xác nhận

- Test Results/Runner phải kiểm tra cả HTTP status và code trong JSON. HTTP 200 ở API khác không đồng nghĩa CRUD này đã được kiểm thử.
- Request đỏ: xem response và request trước đó. Nếu login/token sai hoặc request tạo không thành công, xử lý lỗi tiên quyết trước.
- Request skip: chưa được kiểm thử, không coi là PASS.
- Collection bao phủ happy path, một số validation, không thay thế toàn bộ suite Java/PostgreSQL hoặc kiểm thử đồng thời.
- Người tạo file chỉ kiểm tra cấu trúc JSON, cú pháp script và payload mẫu tại thời điểm bàn giao; chưa chạy collection bằng token/database thật.
