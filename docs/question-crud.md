# Question CRUD — hợp đồng và cách kiểm tra

Đợt này triển khai CRUD câu hỏi thủ công theo controller → service → service/impl → repository.
Mapping DTO được viết tường minh trong service theo cách đang dùng trong dự án.
Chưa triển khai duyệt, import, AI, CRUD rubric hoặc quản trị phân công môn học.

## Database: cần phê duyệt riêng trước khi áp dụng

- Không có migration tự chạy; `spring.jpa.hibernate.ddl-auto` giữ nguyên `none`.
- File đề xuất: `migrations/20261005_question_crud.sql`.
- SQL khởi tạo mới: `database/AIVES_DB_POSTGRESQL_FINAL.sql`.
- Diagram bổ sung: trang `Question CRUD - 2026-10-05 (proposed)` trong `diagrams/All_Diagram.drawio`.
- API mới cần schema đề xuất; không chạy được trên schema cũ chưa nâng cấp.
- Trước khi áp dụng: sao lưu, kiểm tra Bloom cũ và phân loại nguồn/trạng thái chưa xác định.
- Migration dừng nếu có Bloom ngoài bốn mức MVP; không tự đổi nghĩa dữ liệu.
- `is_ai_generated = true` được chuyển thành nguồn `AI`; `is_approved = true` thành `APPROVED`.
  Những trường hợp còn lại chưa xác minh được giữ null ở cột mới, không tự suy ra MANUAL hay DRAFT.
- Câu có `status = null` không được sửa/xóa bằng CRUD. Admin cần duyệt phương án phân loại dữ liệu riêng.
- Giảng viên phải có bản ghi trong `lecturer_subject_assignments` mới sử dụng được môn đó.
  Không tự gán tất cả giảng viên vào mọi môn. Việc nhập phân công cần được chủ dự án cho phép.
- Chưa thay đổi các API xóa môn/người dùng cũ. Phải rà soát cascade của các luồng đó trước khi triển khai ngân hàng vào bài thi.

## Endpoint

Các đường dẫn dưới đây tính từ context path hiện có của ứng dụng.

| Method | Đường dẫn | Thành công |
|---|---|---|
| POST | `/questions` | 201, Location và `ApiResponse<QuestionResponse>` |
| GET | `/questions` | 200, `ApiResponse<PageResponse<QuestionSummaryResponse>>` |
| GET | `/questions/{id}` | 200, `ApiResponse<QuestionResponse>` |
| PATCH | `/questions/{id}` | 200, `ApiResponse<QuestionResponse>` |
| DELETE | `/questions/{id}` | 200, thông báo ổn định, kể cả khi đã xóa |

Admin quản lý toàn bộ nhưng không được bỏ qua điều kiện trạng thái/tham chiếu bài thi.
Giảng viên chỉ xem trong môn được phân công: câu của mình hoặc câu APPROVED chưa lưu trữ của người khác.
Chỉ chủ sở hữu hoặc admin được sửa/xóa. Student bị chặn toàn bộ.
Service kiểm tra lại role hiện tại trong database, không chỉ tin role có sẵn trong JWT.
Luồng signup cũ vẫn nhận role từ request như hạn chế đã được thống nhất hoãn xử lý; cần sửa trước khi mở đăng ký công khai.

## Tạo thủ công

```json
{
  "subjectId": 1,
  "content": "Giải thích cách dùng HashTable trong bài toán Two Sum.",
  "referenceAnswer": "Lưu các phần tử đã gặp để tra cứu phần bù.",
  "topics": ["Array", "HashTable"],
  "bloomLevel": "UNDERSTAND",
  "answerTimeLimitSeconds": 120
}
```

Backend gán MANUAL, DRAFT, tác giả, thời điểm và version. Trường ngoài DTO bị từ chối 400,
kể cả `status`, `createdById`, `isApproved`, `sourceType` hoặc `rubric`.
POST không hỗ trợ Idempotency-Key: gửi lại một POST là tạo một câu hỏi mới.
Không có unique nội dung vì người dùng được phép sao chép câu hỏi.

## PATCH và concurrency

```json
{
  "expectedVersion": 0,
  "referenceAnswer": null,
  "topics": [],
  "answerTimeLimitSeconds": 180
}
```

- Không gửi trường: giữ nguyên; gửi null: bỏ giá trị tùy chọn; topics `[]`: xóa nhãn.
- `content` và `topics` không nhận null; PATCH phải có ít nhất một trường cần sửa.
- Không đổi môn, người tạo, nguồn hay trạng thái qua PATCH.
- Chỉ sửa DRAFT chưa lưu trữ và chưa có tham chiếu bài thi.
- `expectedVersion` bắt buộc. Version cũ trả 409; tải lại rồi quyết định cập nhật.
- Yêu cầu không làm đổi giá trị không tăng version, timestamp hoặc tạo nhật ký.
- PATCH thành công rồi gửi lại version cũ sẽ trả 409, không ghi đè lần nữa.
- Khóa hàng khi cập nhật/xóa kết hợp `@Version`; `@Version` không phải snapshot lịch sử bài thi.
- DELETE lặp lại khi ID không tồn tại trả 200; tài khoản vẫn phải hợp lệ và có role được phép.
- Không dùng DELETE để lưu trữ câu đã dùng. API lưu trữ và chuyển trạng thái thuộc đợt tiếp theo.

## Giới hạn và tìm kiếm

- Nội dung: 1–10.000 ký tự, không chỉ khoảng trắng. Đáp án: tối đa 20.000 ký tự.
- Tối đa 10 nhãn đầu vào, mỗi nhãn 1–100 ký tự; Unicode NFC, strip, loại trùng không phân biệt hoa thường.
- Bloom: REMEMBER, UNDERSTAND, APPLY, ANALYZE hoặc null ở bản nháp.
- Thời gian: số nguyên dương hoặc null; chưa có nghiệp vụ bấm giờ/hết giờ.
- `page`: 0–1.000.000; `size`: 1–100, mặc định 20.
- Bộ lọc: `subjectId`, `createdById`, `topic`, `bloomLevel`, `status`, `sourceType`, `keyword`, `archived`.
- `archived=false` mặc định; chọn true chỉ đọc được dữ liệu lưu trữ thuộc phạm vi quyền.
- `sort`: `id,asc`, `id,desc`, `createdAt,asc`, `createdAt,desc`, `updatedAt,asc`, `updatedAt,desc`.
- Từ khóa tìm nội dung theo nghĩa ký tự thường; `%` và `_` không trở thành wildcard do người gọi cung cấp.
- Tổng số bản ghi cũng chịu điều kiện quyền, không trả tổng của toàn bộ ngân hàng.

## Lỗi

| HTTP | Trường hợp |
|---|---|
| 400 | JSON sai, field ngoài DTO, enum/ID/validation không hợp lệ |
| 401 | Không xác thực hoặc tài khoản đã mất |
| 403 | Sai role, chưa phân công môn, sửa câu đã duyệt của người khác |
| 404 | Câu không tồn tại/không được xem; môn không tồn tại khi admin tạo |
| 409 | Version cũ, không phải bản nháp, đã lưu trữ, câu đã dùng hoặc xung đột dữ liệu |
| 500 | Lỗi nội bộ; không trả SQL hoặc thông tin hạ tầng |

## Test cô lập

Dùng JDK 21 như `pom.xml`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
mvn test '-Dtest=Question*Test'
```

`QuestionControllerTest` dùng MockMvc và SecurityConfig thật, service/decoder giả lập.
`QuestionServiceIntegrationTest` dùng service/repository thật và H2 PostgreSQL mode trong bộ nhớ.
`QuestionTransactionTest` kiểm tra rollback khi ghi nhật ký thất bại và hai request cập nhật/xóa đồng thời.
Kết quả lần kiểm tra: 56 test, 0 thất bại, 0 lỗi, 0 bỏ qua (35 HTTP, 18 tích hợp, 3 transaction).
Các test này không nạp application.yaml, không gọi database hiện tại và không gọi AI.
Không chạy toàn bộ suite mặc định: `AviesBackendApplicationTests` có truy cập database và AI thật.
H2 không thay thế kiểm tra migration PostgreSQL; file migration vẫn cần được duyệt và kiểm thử
trên database PostgreSQL tạm trước khi áp dụng vào database đang dùng.
