# API phân công môn học cho giảng viên

## 1. Phạm vi

- Chỉ bổ sung backend; không thay đổi frontend, schema, migration hoặc database Supabase.
- Dùng bảng `aives.lecturer_subject_assignments` hiện có: `id`, `lecturer_id`, `subject_id`.
- Giữ unique constraint `(lecturer_id, subject_id)`, hai khóa ngoại và `ON DELETE RESTRICT` hiện có.
- Giữ nguyên API `/subjects` đang được các màn hình khác sử dụng. Dropdown Learning Materials và Question Bank phải chuyển sang API `my-subjects` bên dưới khi tích hợp frontend.
- Không coi lọc dropdown là cơ chế phân quyền cho API nội dung. Các API Question/Learning Material vẫn cần kiểm tra quyền riêng ở backend; đợt thay đổi này không sửa nghiệp vụ các API đó.

Cập nhật bổ sung: Learning Materials đã có kiểm tra phân công tại service cho GET/upload. Sau thu hồi, lecturer nhận `403 / 1101` khi gọi các API này; xem [phân quyền Learning Materials](learning-material-access.md). Quyền sửa tiêu đề và xóa vẫn chỉ dành cho ADMIN.

## 2. Hợp đồng bốn API

Các đường dẫn dưới đây là đường dẫn controller; ghép thêm context path của ứng dụng nếu có. Gửi `Authorization: Bearer <access_token>` và `Content-Type: application/json` cho POST.

| Method | Đường dẫn | Vai trò | Ý nghĩa |
| --- | --- | --- | --- |
| POST | `/lecturer-subject-assignments` | ADMIN | Bổ sung các môn được chọn, không thay thế môn cũ |
| POST | `/lecturer-subject-assignments/revocations` | ADMIN | Thu hồi các môn được chọn, không xóa nội dung |
| GET | `/lecturer-subject-assignments` | ADMIN | Xem tất cả phân công hoặc lọc bằng `lecturerId` |
| GET | `/lecturer-subject-assignments/my-subjects` | LECTURER | Xem các môn của chính người đăng nhập |

### POST phân công và thu hồi

Hai API dùng cùng cấu trúc request:

```json
{
  "lecturerId": 11,
  "subjectIds": [1, 4]
}
```

Các ID trên chỉ là ví dụ; cần chọn tài khoản/môn thực sự tồn tại trong môi trường test.

- `lecturerId`: bắt buộc, số nguyên dương, user tồn tại và có vai trò hiện tại là `LECTURER`.
- `subjectIds`: bắt buộc, từ 1 đến 100 phần tử, mỗi phần tử là ID dương và không null. ID trùng được xử lý một lần. Giới hạn 100 tính trước khi loại trùng.
- Mọi môn phải tồn tại, kể cả khi đang thu hồi một phân công vốn chưa tồn tại. Nếu bất kỳ môn nào không tồn tại, toàn bộ yêu cầu thất bại và không ghi một phần.
- Phân công đã tồn tại hoặc phân công cần thu hồi đã vắng mặt đều trả **HTTP 200**, không tạo trùng và không lỗi 500.
- Response có `changedSubjectIds` (thực sự thay đổi lần này) và `unchangedSubjectIds` (đã ở trạng thái mong muốn), sắp xếp ID tăng dần.
- Idempotent nghĩa là trạng thái sau khi gửi lặp không thay đổi thêm; số môn thực sự thay đổi trong response có thể khác lần đầu.

Ví dụ gửi lại khi hai môn đã được phân công:

```json
{
  "code": 1000,
  "message": "Đã xử lý phân công; các môn đã được phân công trước đó được giữ nguyên.",
  "result": {
    "lecturerId": 11,
    "changedSubjectIds": [],
    "unchangedSubjectIds": [1, 4]
  }
}
```

Response thu hồi có thông báo: không xóa câu hỏi/rubric/tài liệu, nhưng giảng viên không còn quyền truy cập Question trong môn đã thu hồi, kể cả câu của mình. Frontend nên hiển thị cảnh báo này trước khi admin xác nhận; backend không thể tự hiển thị hộp thoại.

### GET của admin

```text
GET /lecturer-subject-assignments?page=0&size=20
GET /lecturer-subject-assignments?lecturerId=11&page=0&size=20
```

- Không có `lecturerId`: trả các bản ghi phân công của tất cả giảng viên; không liệt kê giảng viên chưa có phân công.
- Có `lecturerId`: kiểm tra tài khoản tồn tại và là giảng viên. Giảng viên hợp lệ chưa có môn trả danh sách rỗng, không phải lỗi.
- Thứ tự ổn định theo ID phân công tăng dần.
- Mỗi phần tử gồm `id`, `lecturerId`, `lecturerUsername`, `lecturerFullName`, `subject` (`id`, `code`, `name`, `description` nếu có). Không trả entity hoặc thông tin mật của tài khoản.

### GET của lecturer dùng cho dropdown

```text
GET /lecturer-subject-assignments/my-subjects?page=0&size=100
```

- Không nhận `lecturerId` làm bộ lọc. Danh tính lấy từ username trong authentication và đối chiếu user hiện tại trong database.
- Gửi thêm `lecturerId` không đổi được người sở hữu danh sách; query param này không được sử dụng.
- Lọc theo giảng viên ngay trong truy vấn database, trước khi phân trang.
- Sắp xếp theo mã môn rồi ID phân công. Chưa có phân công trả HTTP 200 với `items: []`.
- Frontend dùng `result.items` cho dropdown, tải các trang tiếp theo nếu `page + 1 < totalPages`. Không tự fallback sang `/subjects` khi danh sách rỗng.

```json
{
  "code": 1000,
  "result": {
    "items": [{"id": 1, "code": "SWD392", "name": "Software Architecture and Design"}],
    "page": 0,
    "size": 100,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

Hai GET dùng chung envelope phân trang: `items`, `page`, `size`, `totalElements`, `totalPages`. Mặc định `page=0`, `size=20`; `page` từ 0 đến 1.000.000, `size` từ 1 đến 100. ADMIN không dùng endpoint `my-subjects`; sử dụng GET admin và bộ lọc giảng viên.

## 3. Phân quyền, transaction và đồng thời

1. Controller kiểm tra authority bằng method security và chỉ gọi service.
2. Service kiểm tra lại user/role hiện hành trong database. Token cũ có authority ADMIN nhưng tài khoản đã bị đổi role sẽ bị từ chối.
3. Mỗi POST chạy trong một transaction và khóa dòng user đích bằng `SELECT ... FOR UPDATE`. Hai yêu cầu thêm/thu hồi cùng giảng viên qua các API này được tuần tự hóa, kể cả khi backend có nhiều instance.
4. Đọc và khóa các môn theo ID tăng dần, xác minh toàn bộ trước khi thêm/xóa liên kết. Khóa giữ môn không bị xóa trong lúc xử lý.
5. Lấy các phân công hiện có, chỉ thêm phần thiếu hoặc xóa phần đã có; flush trước khi trả kết quả. Lỗi cuối lô vẫn rollback toàn bộ.
6. Không gọi repository câu hỏi, rubric hoặc tài liệu để xóa nội dung. Phân công khác ngoài danh sách yêu cầu và phân công của giảng viên khác được giữ nguyên.

Unique constraint vẫn là lớp bảo vệ cuối. Các công cụ ghi SQL thủ công không tuân theo khóa của API có thể gây xung đột; khi đó trả 409 an toàn. Không hứa mọi thay đổi bên ngoài API đều idempotent. Nếu thêm và thu hồi cùng môn đồng thời, thao tác được thực thi sau quyết định trạng thái cuối, không có ưu tiên riêng cho thu hồi.

Không tắt RLS, không tạo policy mở dữ liệu cho client. Backend tiếp tục dùng cơ chế kết nối database hiện có; frontend không ghi trực tiếp bảng phân công qua Supabase Data API.

## 4. Lỗi và mã phản hồi

| HTTP | Code | Tình huống |
| --- | --- | --- |
| 200 | 1000 | Thành công, kể cả gửi lặp không thay đổi dữ liệu |
| 400 | 1002 | JSON, ID, danh sách môn hoặc phân trang không hợp lệ |
| 400 | 1200 | Tài khoản đích không có vai trò LECTURER |
| 401 | 1003 | Chưa xác thực hoặc tài khoản người gọi không còn tồn tại |
| 403 | 1004 | Người gọi không có quyền dùng API |
| 404 | 1006 | Không tìm thấy tài khoản giảng viên đích |
| 404 | 1016 | Có môn không tồn tại trong lô yêu cầu |
| 409 | 1201 | Xung đột khóa/constraint khi xử lý; tải lại dữ liệu và thử lại |

Các lỗi JWT ở filter dùng cơ chế xác thực hiện có. Exception handler riêng của API phân công không làm thay đổi lỗi các controller khác và không trả SQL/stacktrace cho client.

## 5. Kiểm thử và bàn giao

Yêu cầu JDK 21, Maven; mở Docker Desktop ở chế độ Linux containers nếu chạy PostgreSQL test. Không cần tự tạo/start container database.

```powershell
# Test nhanh: H2, MockMvc và các test hiện có; không gọi Supabase/AI.
mvn test

# Chạy cả test PostgreSQL thật bằng Testcontainers tạm, tự tạo và tự dọn.
mvn clean verify -Ppostgres-it
```

- `LecturerSubjectAssignmentIntegrationTest`: bốn endpoint với controller/service/repository thật trên H2; chỉ giả lập xác thực JWT, kiểm tra ma trận quyền, kiểm tra role database, payload lỗi, phân trang, nhiều–nhiều, gửi lặp, lô không hợp lệ và thu hồi quyền xem câu hỏi.
- `LecturerSubjectAssignmentPostgresIT`: JWT đăng nhập thật, PostgreSQL 17 tạm, schema từ migration hiện có; kiểm tra thêm/thu hồi đồng thời, rollback khi lỗi giữa lô, FK/unique/restrict và giữ câu hỏi/rubric sau thu hồi.
- Các constraint/bảng gây lỗi có chủ đích trong PostgreSQL test chỉ được tạo trong container tạm rồi dọn; không thực hiện trên Supabase.
- Không chạy profile `external-services` để xác minh chức năng này.

Kết quả xác minh ngày 15/10/2026: bộ kiểm thử đầy đủ chạy thành công **118 test** (93 test nhanh và 25 test PostgreSQL), không failure/error/skipped trong các nhóm được chạy. Trong đó có **37 test mới** cho phân công (29 H2/HTTP và 8 PostgreSQL). Profile gọi dịch vụ bên ngoài không được chạy; kết quả này không phải xác nhận đã kiểm thử trên Supabase thật.

Lưu ý môi trường build: thống nhất JDK 21 cho IDE, Maven runner và terminal (`mvn -version`). Trong quá trình xác minh từng gặp file `.class` chứa `Unresolved compilation problems / Flexible Constructor Bodies` liên quan source level 21/25. Nếu gặp lại, kiểm tra cấu hình Java/Lombok của IDE và chạy build sạch bằng JDK 21; tránh để compiler của IDE ghi đè `target/classes` trong lúc Maven test. Không đổi schema hoặc nâng Java của dự án chỉ để xử lý output build cũ.

Checklist tích hợp frontend sau này:

- [ ] Admin tải GET phân công để biết trạng thái hiện tại.
- [ ] Nút Assign gửi POST bổ sung; thao tác thu hồi gọi POST `/revocations` riêng, không suy diễn rằng môn không chọn phải bị thu hồi.
- [ ] Hiển thị cảnh báo mất quyền Question trước khi thu hồi và tải lại danh sách sau thành công.
- [ ] Hai dropdown lecturer gọi `/my-subjects`, hỗ trợ phân trang, trạng thái rỗng và lỗi quyền.
- [ ] Kiểm tra bằng ít nhất hai giảng viên có tập môn khác nhau; không dùng tài khoản admin để thử endpoint của lecturer.

Giới hạn hiện tại: chưa lưu ai phân công/thời điểm/lịch sử, không xây API đổi role hoặc xử lý phân công cũ khi đổi role. Bổ sung các dữ liệu audit này về sau cần thiết kế và xin phép migration riêng.
