# Feature 2 / P0 — Student Assignment & Basic Exam Slot Scheduling

Ngày triển khai: 19/10/2026. Baseline đã kiểm tra: nhánh `dev/An`, HEAD `8e46f23`, worktree sạch trước đợt này.

## 1. Phạm vi

Bổ sung chọn sinh viên, gán batch nguyên tử, chia slot cơ bản, xem lịch và thu hồi bài chưa dùng. Dùng nguyên schema/migration P0.3 đã có; không tạo hay chạy migration mới, không sửa entity hoặc grants/RLS, không kết nối Supabase.

Kiến trúc controller → StudentExamService → service/impl → repository; DTO riêng cho input/output, không serialize entity. Skill api-and-interface-design được dùng để giữ hợp đồng, phân trang và mã lỗi nhất quán, không thay stack/dependency của dự án.

Không triển khai chọn câu, start session/student exam, AI, STT/TTS/WebSocket, grading, frontend hoặc lịch nhiều phòng.

## 2. API và phân quyền

Mọi request cần Authorization: Bearer JWT. JSON dùng camelCase. Danh tính lấy từ JWT username; role phải còn đúng trong database và authority JWT phải khớp.

| Method / endpoint | Quyền | Response |
| --- | --- | --- |
| GET /students/eligible | LECTURER hiện hành | 200, ApiResponse<PageResponse<EligibleStudentResponse>> |
| POST /exam-sessions/{id}/students | LECTURER sở hữu và còn phân công môn | 200, ApiResponse<StudentAssignmentResponse> |
| GET /exam-sessions/{id}/students | LECTURER sở hữu hoặc ADMIN | 200, ApiResponse<PageResponse<StudentExamAssignmentResponse>> |
| DELETE /student-exams/{id} | LECTURER sở hữu parent, còn phân công | 200, message, không có result |
| GET /student-exams/me | STUDENT hiện hành | 200, ApiResponse<PageResponse<MyStudentExamResponse>> |

| Người gọi | Eligible | Gán / thu hồi | Danh sách trong phiên | Lịch cá nhân |
| --- | --- | --- | --- | --- |
| Owner LECTURER còn phân công | Được | Được nếu điều kiện trạng thái/lịch hợp lệ | Được | 403 |
| Owner LECTURER đã thu hồi môn | Được tra cứu STUDENT nói chung | 403 / 1101 | Vẫn được đọc lịch sử | 403 |
| LECTURER khác cùng môn | Được | 404 đối với phiên/bài ngoài quyền | 404 | 403 |
| ADMIN | 403 | 403 | Được đọc mọi phiên tồn tại | 403 |
| STUDENT | 403 | 403 | 403 | Chỉ bài của chính mình |
| Không đăng nhập hoặc user đã mất | 401 | 401 | 401 | 401 |

Không nhận lecturerId hoặc studentId để thay phạm vi đọc. Query studentId gửi thêm vào /me không thay scope; query lecturerId không mở quyền quản lý. ADMIN chỉ đọc, không nhận quyền ghi ngầm.

### Eligible students

GET `/students/eligible?query=Lan&page=0&size=20`

- Chỉ user hiện có role STUDENT; lọc và count ngay ở database trước phân trang.
- query tùy chọn, tối đa 100 ký tự, strip, không phân biệt hoa thường, tìm username hoặc fullName. %, _ và ! là literal, không mở rộng wildcard. NUL bị từ chối 400.
- Không tìm hoặc trả email, password hash, Google subject hoặc token.
- Không lọc enrollment vì schema hiện không có student-subject enrollment.
- Thứ tự ID tăng dần.

Ví dụ:

```json
{
  "code": 1000,
  "result": {
    "items": [{"id": 4, "username": "student1", "fullName": "Sinh viên Một"}],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
  }
}
```

Ba GET dùng page=0..1000000, size=1..100 (mặc định 20); request sai trả 400/1002.

### Gán batch

POST `/exam-sessions/123/students`

```json
{"studentIds": [4, 6, 4]}
```

- 1..100 phần tử JSON integer Long dương; null, chuỗi, số lẻ hoặc field ngoài DTO bị từ chối.
- Giới hạn 100 áp dụng trước gộp trùng, tránh request lớn bằng một ID lặp nhiều lần.
- Gộp ID theo lần xuất hiện đầu tiên. Mọi user duy nhất phải tồn tại và đang STUDENT; chỉ một ID sai thì không lưu bất kỳ bài mới nào.
- Parent phải UPCOMING và không có StudentExam đã bắt đầu/hoàn tất theo status hoặc startedAt/completedAt.
- Chỉ tạo StudentExam ASSIGNED, không tạo câu hỏi, điểm, audio hoặc timestamp bắt đầu thi thực tế.
- Thành công luôn HTTP 200: created là bài mới, unchanged là bài đã có. Mỗi danh sách giữ thứ tự tương ứng trong batch.

Ví dụ phản hồi khi ID 4 mới, ID 6 đã tồn tại:

```json
{
  "code": 1000,
  "result": {
    "sessionId": 123,
    "created": [{
      "id": 801,
      "studentId": 4,
      "studentUsername": "student1",
      "studentFullName": "Sinh viên Một",
      "status": "ASSIGNED",
      "slotOrder": 1,
      "scheduledStartAt": "2026-11-01T01:00:00Z",
      "scheduledEndAt": "2026-11-01T01:20:00Z"
    }],
    "unchanged": [{
      "id": 800,
      "studentId": 6,
      "studentUsername": "student2",
      "studentFullName": "Sinh viên Hai",
      "status": "ASSIGNED",
      "slotOrder": 2,
      "scheduledStartAt": "2026-11-01T01:20:00Z",
      "scheduledEndAt": "2026-11-01T01:40:00Z"
    }]
  }
}
```

ID/thời gian chỉ minh họa, phải dùng dữ liệu test thật được phép. Gửi lại cùng danh sách sau commit trả created=[], unchanged chứa các bài cũ; không tốn slot mới, không đổi ID/lịch. Batch lặp vẫn phải hợp quyền, role và lịch tại thời điểm gọi. Nếu phiên đã bắt đầu/qua giờ, role đổi, thu hồi môn hoặc dữ liệu legacy không hợp lệ thì không bảo đảm 200. Tranh chấp transaction trả 409; tải lại rồi gửi lại có chủ đích, không xem mọi lỗi là thành công.

### Danh sách trong phiên

GET `/exam-sessions/123/students?page=0&size=20`

DTO phần tử giống created/unchanged ở trên; envelope dùng result.items và đủ metadata PageResponse. Order slotOrder tăng dần, null cuối, ID tăng dần phá hòa. Owner đọc được dù môn bị thu hồi; ADMIN cũng đọc được. STUDENT không được xem danh sách các sinh viên khác.

### Thu hồi bài chưa dùng

DELETE `/student-exams/801`

```json
{"code": 1000, "message": "Đã thu hồi bài thi chưa sử dụng; lịch sinh viên khác được giữ nguyên."}
```

Chỉ khi parent UPCOMING, owner còn phân công và child ASSIGNED, startedAt/completedAt/aiSuggestedScore/finalScore/aiFeedback/audioRecordUrl đều null, đồng thời không có StudentExamQuestion nào. Điểm bằng 0 hoặc chuỗi bằng rỗng vẫn là dữ liệu cần giữ, không coi là chưa dùng.

Có câu đã chọn cũng chặn xóa dù chưa có transcript. Vì transcripts tham chiếu StudentExamQuestion bằng FK, điều kiện bảo thủ này giữ cả bằng chứng bên dưới; không cần đọc hay trả transcript. Khóa child FOR UPDATE chặn FK insert câu đồng thời.

Không dồn slot khác, không giảm slotOrder của người còn lại. Gọi lại ID đã xóa trả 404/1400, không phải 500. Không nhận thao tác bulk-delete.

### Lịch cá nhân

GET `/student-exams/me?page=0&size=20`

Bao gồm tất cả bài đã gán cho chính sinh viên, kể cả IN_PROGRESS/COMPLETED để giữ lịch sử; không chỉ lọc status ASSIGNED. Thứ tự ID giảm dần, count cũng theo owner trước phân trang.

```json
{
  "code": 1000,
  "result": {
    "items": [{
      "id": 801,
      "sessionId": 123,
      "sessionTitle": "SWD392 Viva",
      "subjectId": 1,
      "subjectCode": "SWD392",
      "subjectName": "Software Architecture and Design",
      "sessionStatus": "UPCOMING",
      "status": "ASSIGNED",
      "slotOrder": 1,
      "scheduledStartAt": "2026-11-01T01:00:00Z",
      "scheduledEndAt": "2026-11-01T01:20:00Z"
    }],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
  }
}
```

Không trả câu hỏi, đáp án tham khảo, rubric, transcript, điểm, URL audio, email hoặc dữ liệu sinh viên khác. Không cần current lecturer-subject assignment để sinh viên đọc lịch đã được gán.

## 3. Thuật toán xếp lịch và dữ liệu legacy

1. Cần start/end/duration đầy đủ, end > start, duration > 0 và start còn trong tương lai theo instant.
2. Slot k (k từ 1) là [start+(k-1)*duration, start+k*duration). Hai slot sát nhau không chồng lấn.
3. Capacity = floor((end-start)/duration), chỉ tính slot trọn vẹn. Phần dư cuối cửa sổ không được cấp. Miền hỗ trợ slotOrder tối đa Integer.MAX_VALUE do cột INTEGER.
4. Kiểm tra mọi lịch đã tồn tại trong phiên: slotOrder có giá trị, nằm trong capacity, duy nhất, start/end đầy đủ và trùng instant của lưới slot. Không tự chuẩn hóa hoặc cập nhật lịch cũ.
5. Giữ toàn bộ slot cũ, cấp sinh viên mới vào các slot trống nhỏ nhất theo thứ tự ID duy nhất trong request.
6. Nếu không đủ chỗ cho toàn batch mới, trả 409/1402 và không insert một phần.

Ví dụ phiên 08:00–09:05, duration 20: capacity 3. Slot 1=08:00–08:20, 2=08:20–08:40, 3=08:40–09:00. Xóa slot 2 không đổi slot 3; lượt gán mới dùng lại slot 2.

OffsetDateTime và TIMESTAMPTZ được so theo instant; response có thể dùng Z thay vì +07:00. Không dùng timezone mặc định của máy để chế tạo offset hoặc cắt theo ngày địa phương.

Legacy:
- GET giữ nguyên null lịch, không lấy createdAt/startedAt để giả lập scheduledStartAt.
- Parent thiếu lịch, qua giờ hoặc có child thiếu/ngoài/lệch lưới lịch: assignment trả 409/1403, kể cả request không cần slot mới.
- Child ASSIGNED legacy chưa có bất kỳ bằng chứng vẫn có thể được owner thu hồi theo cùng điều kiện an toàn; không tự xóa hàng cũ khi gặp lỗi.
- Parent legacy đang có child không thể dùng PATCH để tự lấp lịch vì quy tắc khóa lịch. Cần xử lý dữ liệu riêng có review/phê duyệt, hoặc thu hồi các bài thật sự chưa dùng rồi cập nhật lịch parent.
- Không triển khai tối ưu lịch liên phiên, chống trùng lịch cá nhân giữa các phiên khác nhau hoặc đa phòng. Bảo đảm không chồng slot trong cùng một phiên.

## 4. Tương thích Exam Session CRUD

- Giữ các route, DTO, lỗi cũ và quy tắc owner/current assignment/UPCOMING.
- Khi có bất kỳ StudentExam, PATCH không được thay start/end/duration, kể cả bài legacy chưa có slot.
- Gửi lại duration đúng giá trị hoặc timestamp cùng instant (offset khác) không bị xem là đổi lịch.
- Title, maxMainQuestions và maxFollowUps vẫn được sửa nếu điều kiện CRUD cũ cho phép; có bài bắt đầu/hoàn tất thì vẫn 409/1302.
- Validation toàn trạng thái sau PATCH và yêu cầu lịch tương lai của CRUD giữ nguyên; không tự mở sửa legacy hoặc phiên quá giờ.
- DELETE parent vẫn chặn nếu có bất kỳ StudentExam; không lợi dụng ON DELETE CASCADE để xóa lịch sử.
- Tách phần xác thực/khóa cũ sang ExamAccessPolicy dùng chung thay vì để hai service triển khai hai giao thức khác nhau. Không đổi business rules của Feature 1.

## 5. Khóa và cạnh tranh

Transaction ghi timeout 10 giây, không gọi external service:

- Gán: lecturer user FOR UPDATE NOWAIT → subject FOR SHARE → kiểm tra phân công → parent ExamSession FOR UPDATE NOWAIT + refresh → target student users FOR SHARE NOWAIT theo ID tăng dần + refresh → validate role/lịch/batch → insert child → flush/commit.
- Thu hồi: đọc scalar parent ID của child (không khóa child) → lecturer user → subject → parent như trên → child StudentExam FOR UPDATE NOWAIT + refresh → kiểm tra evidence → delete/flush/commit.
- CRUD cũ dùng cùng policy và parent locks. Cùng lecturer đang ghi phiên khác nhau vẫn có thể bị 409 do khóa user thô, phù hợp MVP.
- Student row lock ngăn role/FK user bị thay đổi giữa validate và insert; NOWAIT tránh chờ vòng khi user đích lại là lecturer của transaction khác. Không thêm quy trình update role mới.
- Khóa parent chống hai batch giành cùng slot, chống assignment chạy xuyên PATCH lịch hoặc DELETE parent.
- Khóa child mạnh chống insert câu FK KEY SHARE chạy xuyên kiểm tra/xóa.
- UNIQUE(session_id,student_id), UNIQUE(session_id,slot_order), FK và CHECK hiện có là lớp cuối. Vi phạm/timing race được ánh xạ 409/1303, rollback nguyên batch; không trả SQL hoặc stacktrace.

Đối với các writer tương lai: giữ lecturer → subject → parent → student users (nếu cần, ID tăng dần) → child. Không khóa child rồi quay về parent. Start/grade/evidence writer phải dùng giao thức parent/child tương ứng, không chỉ update child trực tiếp.

Giới hạn: không có optimistic version hoặc ETag; khóa bảo vệ transaction chồng nhau, không phát hiện mọi form cũ gửi sau commit. SQL thủ công hoặc writer bỏ qua giao thức có thể phá giả định trạng thái. Khóa/FK không thay thế mọi business rule.

## 6. Mã lỗi

| HTTP | Code | Ý nghĩa |
| --- | --- | --- |
| 400 | 1002 | JSON, null/kiểu ID, batch, path, query hoặc phân trang sai |
| 400 | 1401 | Có user hiện không phải STUDENT |
| 401 | 1003 | Chưa xác thực hoặc user không còn tồn tại |
| 403 | 1004 | Vai trò DB/JWT không phù hợp |
| 403 | 1101 | Owner không còn phân công môn khi ghi |
| 404 | 1006 | Có user đích không tồn tại |
| 404 | 1300 | Parent mất hoặc ngoài quyền |
| 404 | 1400 | Child mất hoặc ngoài quyền thu hồi |
| 409 | 1301 | Parent không UPCOMING |
| 409 | 1302 | Assignment/PATCH bị chặn vì có bài đã bắt đầu |
| 409 | 1303 | Khóa, timeout hoặc vi phạm ràng buộc khi có cạnh tranh |
| 409 | 1402 | Thiếu sức chứa toàn batch |
| 409 | 1403 | Lịch parent/legacy không sẵn sàng để gán |
| 409 | 1404 | Child đã dùng hoặc có bằng chứng/câu đã gán |
| 409 | 1405 | PATCH thay đổi lịch parent đang có child |
| 500 | 9999 | Lỗi ngoài dự kiến, chỉ trả thông báo chung |

ExamSessionExceptionHandler được mở rộng phạm vi tới đúng ba controller mới; không sửa global handler của Feature 1. Controller mới có @Operation mô tả quyền/contract và DTO typed cho OpenAPI.

## 7. Swagger/Postman smoke test

Chỉ dùng tài khoản test được phép và lưu ý backend có thể đang trỏ Supabase chung. Không tự dựng started/evidence fixture bằng cách sửa dữ liệu thật.

1. Run backend, mở /swagger-ui/index.html. Đăng nhập POST /auth/signin, copy result.token và Authorize bằng JWT của LECTURER test.
2. GET /lecturer-subject-assignments/my-subjects để chọn môn đã được phân công.
3. POST /exam-sessions với đầy đủ lịch tương lai. Ví dụ title="Scheduling smoke", duration=20, window=60 phút, maxMainQuestions=3, maxFollowUps=0. Lưu sessionId.
4. GET /students/eligible?query=&page=0&size=20; chọn hai ID STUDENT thật, gọi là A và B, không giả định các số trong ví dụ có thật.
5. POST /exam-sessions/{sessionId}/students body {"studentIds":[A,B,A]}. Mong đợi 200, created có 2 phần tử, slotOrder=1,2, status ASSIGNED, mỗi slot 20 phút.
6. Gửi lại body đó: 200, created=[], unchanged có 2, ID/slot không đổi.
7. GET /exam-sessions/{sessionId}/students?page=0&size=1: totalElements=2, totalPages=2; trang 0 slot 1, trang 1 slot 2.
8. Dùng token của A gọi GET /student-exams/me. Chỉ thấy bài của A và thông tin lịch/môn; không có câu hỏi/đáp án/rubric.
9. Dùng token A gọi GET danh sách sinh viên của phiên hoặc DELETE /student-exams/{id}: 403.
10. Dùng LECTURER khác gọi GET danh sách phiên: 404. ADMIN đọc được nhưng POST/DELETE bị 403.
11. Trở lại token owner, PATCH parent duration khác: 409/1405. PATCH title hoặc duration giữ nguyên: 200 nếu các điều kiện CRUD khác vẫn hợp lệ.
12. DELETE parent: 409/1302. DELETE đúng StudentExamId của A còn chưa dùng: 200; gửi lại DELETE: 404/1400.
13. GET lịch phiên: B vẫn slot 2, không dồn. Gán lại A: nhận slot 1 trống, ID child mới.
14. Test batch có một ID không tồn tại hoặc không STUDENT: 404/1006 hoặc 400/1401; so count trước/sau không đổi.
15. Test quá capacity bằng các sinh viên chưa có trong phiên: 409/1402, không ghi một phần. Trường hợp lịch legacy không được tự lấp.
16. Dọn các StudentExam test chưa dùng bằng DELETE, rồi DELETE parent test. Không xóa câu hỏi/tài liệu để dọn lịch.

Đổi Authorization khi chuyển vai trò. Với Swagger HTTP bearer chỉ nhập JWT. Postman dùng Bearer Token và body raw JSON. ID trong DELETE là StudentExamId, không phải studentId.

## 8. Kiểm thử và bằng chứng thực thi

Bộ test mới:

- ExamSlotPlannerTest: lưới slot, offset/instant, lỗ trống, capacity, legacy và miền INTEGER.
- StudentExamServiceTest: validate toàn batch trước lưu, role, thiếu user, capacity và bảo vệ evidence.
- StudentAssignmentControllerTest: năm route, DTO JSON nghiêm ngặt, pagination, method security, lỗi không lộ SQL.
- StudentAssignmentPostgresIT: HTTP/service/JPA thật, rollback, scope/count, role hiện hành, lịch, legacy, thu hồi và PATCH/DELETE tương thích.
- StudentAssignmentConcurrencyPostgresIT: batch vs batch/PATCH/DELETE, gỡ vs gán lại, role user đồng thời, evidence/start và hai thứ tự insert câu/FK vs thu hồi.
- StudentAssignmentPostgresConfig tái sử dụng baseline/migration/container image đã khóa digest, Hibernate validate; không dùng cấu hình hoặc URL Supabase.

Lệnh bắt buộc, dùng JDK 21:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd clean verify -Ppostgres-it -B -ntp
```

Kết quả chạy cuối: **BUILD SUCCESS** lúc **12:08:43 +07:00 ngày 19/10/2026**, thời gian 1 phút 39 giây. Biên dịch main/test bằng javac release 21 và đóng gói thành công. PostgreSQL trong Docker là **17.11**.

| Nhóm | Test đạt | Failure / error / skipped |
| --- | --- | --- |
| Test nhanh (Surefire) | 260 | 0 / 0 / 0 |
| PostgreSQL/Testcontainers (Failsafe) | 151 | 0 / 0 / 0 |
| Tổng | **411** | **0 / 0 / 0** |

Trong đó **88 test mới**: ExamSlotPlannerTest 19, StudentExamServiceTest 6, StudentAssignmentControllerTest 27, StudentAssignmentPostgresIT 28 và StudentAssignmentConcurrencyPostgresIT 8. **323 test hồi quy** của Feature 1 và Feature 2 hiện có cũng đạt. Full suite đã chạy thành công hai lượt; lượt cuối sau khi dọn import là bằng chứng phiên bản bàn giao.

Báo cáo XML: target/surefire-reports và target/failsafe-reports. Log full build ở thư mục tạm máy chạy, tên aives-exam-session-full-20261019.log; không commit output build. Ban đầu hệ thống xét duyệt lệnh compile bị quá tải và chưa thực thi, nhưng lệnh full suite sau đó đã được chạy thành công; không còn blocker môi trường.

Không chạy profile external-services, không khởi động backend với cấu hình database chung, không gọi Supabase/AI thật và không test UI/frontend. JWT decoder được mock trong test HTTP mới, còn policy/service/repository/transaction/constraints PostgreSQL chạy thật; nhóm hồi quy auth vẫn giữ nguyên. Không tuyên bố test Docker thay thế việc kiểm thử grants/RLS hoặc triển khai live.

## 9. Git và phần còn lại

Không commit/push/merge tự động. Không sửa frontend, pom.xml, authentication, secrets, application.yaml, entity hoặc file migration.

Nhóm file mới gồm ba controller, StudentExamService/Impl, ba DTO request, bốn DTO response, hai support class, sáu file test/config và tài liệu này. Các file sửa: ErrorCode, ExamSessionExceptionHandler, UserRepository, StudentExamRepository, StudentExamQuestionRepository, ExamSessionServiceImpl, ExamSessionCrudPostgresConfig và tài liệu CRUD bổ sung link/quy tắc khóa lịch.

Chi tiết Git: **21 file mới, 8 file sửa**, tổng 29 đường dẫn, không có file xóa/rename. Đường dẫn tương đối từ backend:

```text
M docs/business_rules/feature2-exam-session-crud.md
M src/main/java/com/avies/backend/exception/ErrorCode.java
M src/main/java/com/avies/backend/exception/ExamSessionExceptionHandler.java
M src/main/java/com/avies/backend/repository/StudentExamQuestionRepository.java
M src/main/java/com/avies/backend/repository/StudentExamRepository.java
M src/main/java/com/avies/backend/repository/UserRepository.java
M src/main/java/com/avies/backend/service/impl/ExamSessionServiceImpl.java
M src/test/java/com/avies/backend/exam/ExamSessionCrudPostgresConfig.java
?? docs/business_rules/feature2-student-assignment-scheduling.md
?? src/main/java/com/avies/backend/controller/EligibleStudentController.java
?? src/main/java/com/avies/backend/controller/StudentAssignmentController.java
?? src/main/java/com/avies/backend/controller/StudentExamController.java
?? src/main/java/com/avies/backend/dto/request/EligibleStudentSearchRequest.java
?? src/main/java/com/avies/backend/dto/request/ExamAssignmentPageRequest.java
?? src/main/java/com/avies/backend/dto/request/StudentAssignmentRequest.java
?? src/main/java/com/avies/backend/dto/response/EligibleStudentResponse.java
?? src/main/java/com/avies/backend/dto/response/MyStudentExamResponse.java
?? src/main/java/com/avies/backend/dto/response/StudentAssignmentResponse.java
?? src/main/java/com/avies/backend/dto/response/StudentExamAssignmentResponse.java
?? src/main/java/com/avies/backend/service/StudentExamService.java
?? src/main/java/com/avies/backend/service/impl/StudentExamServiceImpl.java
?? src/main/java/com/avies/backend/service/support/ExamAccessPolicy.java
?? src/main/java/com/avies/backend/service/support/ExamSlotPlanner.java
?? src/test/java/com/avies/backend/exam/ExamSlotPlannerTest.java
?? src/test/java/com/avies/backend/exam/StudentAssignmentConcurrencyPostgresIT.java
?? src/test/java/com/avies/backend/exam/StudentAssignmentControllerTest.java
?? src/test/java/com/avies/backend/exam/StudentAssignmentPostgresConfig.java
?? src/test/java/com/avies/backend/exam/StudentAssignmentPostgresIT.java
?? src/test/java/com/avies/backend/exam/StudentExamServiceTest.java
```

`git diff --stat` không tính các file untracked. Xem cả `git ls-files --others --exclude-standard` và `git status --short -uall` khi review; không tự stage chỉ để lấy thống kê.

Phần chưa triển khai và cần review riêng:

1. Chọn câu APPROVED, snapshot/version câu hỏi/rubric và chống lặp.
2. Start session/student exam, kiểm tra state, quyền và thời gian thực tế theo cùng lock order.
3. STT/TTS/WebSocket, follow-up AI và chấm điểm/giảng viên chốt điểm.
4. Tích hợp frontend, kiểm thử end-to-end và review thực tế trên môi trường được phép.
5. Rescheduling, nhiều phòng, trùng lịch liên phiên, enrollment và optimistic concurrency chỉ làm sau khi chốt hợp đồng/schema nếu cần.
6. Xử lý dữ liệu legacy có lịch thiếu/sai theo kế hoạch được phê duyệt, không tự sửa trong API assignment.

**STUDENT ASSIGNMENT & SCHEDULING COMPLETE — READY FOR REVIEW**

Dừng sau triển khai và kiểm thử để chờ review; chưa commit/push/merge hoặc triển khai Supabase.
