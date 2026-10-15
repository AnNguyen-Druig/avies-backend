# Phân quyền Learning Materials theo phân công môn

## 1. Phạm vi thay đổi

- Kiểm tra phân công tại service, không chỉ dựa vào dropdown hoặc quyền ở controller.
- Không đổi URL, request/response, định dạng file hỗ trợ hay pipeline xử lý tài liệu hiện có.
- Không thay đổi schema, không cần migration và không áp dụng SQL lên Supabase.
- Dùng bảng `aives.lecturer_subject_assignments` hiện có.

## 2. Quyền trên các API hiện tại

| API | ADMIN | LECTURER |
| --- | --- | --- |
| `GET /materials/subject/{subjectId}` | Xem tài liệu của mọi môn tồn tại | Chỉ xem môn đang được phân công |
| `POST /materials/upload` | Upload vào mọi môn tồn tại | Chỉ upload vào môn đang được phân công |
| `PATCH /materials/{materialId}/title` | Được sửa tiêu đề như trước | Không được phép, kể cả tài liệu của mình |
| `DELETE /materials/{materialId}` | Được xóa như trước | Không được phép, kể cả tài liệu của mình |

Giữ nguyên quy tắc đọc theo môn: lecturer có phân công được xem cả tài liệu do người khác upload trong môn đó. Không bổ sung giới hạn chỉ xem tài liệu của mình.

Với GET/upload, service đọc tài khoản và vai trò hiện hành từ database, kiểm tra môn tồn tại rồi kiểm tra phân công. Kiểm tra này diễn ra trước truy vấn danh sách tài liệu hoặc lưu file/metadata, tách văn bản, chia chunks và gọi AI. ADMIN không cần có bản ghi phân công. Token cũ mang quyền ADMIN/LECTURER không thay thế được vai trò hiện hành trong database.

DELETE được bổ sung kiểm tra ADMIN ở service để không thể bỏ qua quyền bằng lời gọi service trực tiếp. PATCH đã có kiểm tra ADMIN tại service nên giữ nguyên nghiệp vụ hiện có.

## 3. Kết quả khi không đủ quyền

Lecturer chưa được phân công hoặc đã bị thu hồi môn nhận **HTTP 403**, kể cả khi là người upload tài liệu:

```json
{
  "code": 1101,
  "message": "Bạn chưa được phân công môn học này"
}
```

Các lỗi nghiệp vụ hiện có tiếp tục được sử dụng:

| Trường hợp | HTTP / mã nghiệp vụ |
| --- | --- |
| Chưa xác thực | `401 / 1003` |
| Vai trò không được phép | `403 / 1004` |
| Tài khoản trong token không còn tồn tại | `404 / 1006` |
| Môn không tồn tại, người gọi có vai trò phù hợp | `404 / 1016` |
| File rỗng, người gọi đã có quyền trên môn | `400 / 1017` |
| Định dạng file không hỗ trợ, người gọi đã có quyền trên môn | `400 / 1018` |

## 4. Thu hồi và phân công lại

1. ADMIN thu hồi qua `POST /lecturer-subject-assignments/revocations`.
2. Sau khi transaction thu hồi commit, request GET/upload mới của lecturer trên môn đó bị chặn.
3. Thu hồi không xóa file, metadata hoặc chunks. ADMIN vẫn truy cập được tài liệu.
4. Phân công lại qua API phân công khôi phục quyền đọc/upload của lecturer.

Giới hạn cần hiểu: quyền được kiểm tra đầu request; thay đổi này không hủy một upload đã vượt qua kiểm tra và đang xử lý khi có thu hồi đồng thời. Không giữ khóa database dài xuyên suốt việc gọi AI. Thu hồi cũng không thể xóa dữ liệu mà người dùng đã tải về hoặc frontend đã cache trước đó.

Frontend cần refresh dropdown bằng `GET /lecturer-subject-assignments/my-subjects`; khi gặp 403 thì xóa nội dung cũ khỏi màn hình và thông báo mất quyền, không chuyển sang tải tất cả môn để né lỗi. Đợt thay đổi này không sửa frontend. Nếu bổ sung API tải file, preview hoặc đọc chunks sau này, từng API đó cũng phải kiểm tra quyền trước khi trả nội dung.

## 5. Kiểm thử tự động

- `LearningMaterialAccessIntegrationTest`: 21 ca kiểm thử, đi qua controller, filter bảo mật, service và repository thật trên H2 cô lập; upload dùng thư mục tạm.
- Các request có transaction độc lập để kiểm tra thu hồi đã commit, giữ dữ liệu và phân công lại.
- Bao phủ lecturer đúng/sai môn, ADMIN, STUDENT, người chưa xác thực, token mang vai trò cũ, gọi service trực tiếp, file không hợp lệ và lỗi môn/tài khoản không tồn tại.
- JWT, parser, chunking, embedding và batch ghi vector được giả lập. Bộ test này không chứng minh chất lượng parser/AI/pgvector thực tế và không truy cập Supabase.
- Kết quả lần chạy bằng JDK 21: `mvn clean verify -Ppostgres-it` đạt **139 test, 0 failure, 0 error, 0 skipped**: 114 test thông thường (gồm 21 test mới) và 25 test PostgreSQL hồi quy hiện có chạy bằng Testcontainers.

Chạy lại với JDK 21:

```powershell
mvn test
# Kiểm thử thêm PostgreSQL cô lập: cần Docker Desktop đang chạy Linux containers.
mvn verify -Ppostgres-it
```

Không bật profile `external-services` khi chỉ kiểm tra phân quyền. Nếu gặp file `.class` cũ/hỏng, dừng backend trong IDE, kiểm tra JDK rồi dùng `mvn clean verify -Ppostgres-it`; tránh IDE ghi đè output trong lúc Maven build.

## 6. Checklist kiểm thử thủ công

Sử dụng môi trường test và ID thực tế của nhóm; các bước upload/phân công/thu hồi có thay đổi dữ liệu.

1. Phân công lecturer vào môn A, không phân công môn B.
2. Token lecturer gọi GET môn A: 200. Upload file hợp lệ vào A: giữ kết quả xử lý như trước.
3. Cùng token gọi GET/upload môn B: 403, mã 1101; không tạo tài liệu mới.
4. ADMIN thu hồi môn A. Gọi lại GET/upload A bằng token lecturer cũ: 403, mã 1101.
5. Token ADMIN gọi GET môn A: tài liệu vẫn còn. Phân công lại rồi GET bằng lecturer: 200.
6. Lecturer sửa tiêu đề/xóa tài liệu của mình: 403, kể cả khi có phân công. ADMIN vẫn được thao tác như trước.
7. STUDENT nhận 403; request không có token nhận 401. Không chia sẻ token thật trong ảnh/log.
