# Kiểm thử Question CRUD sau migration

## Kết quả thực chạy ngày 2026-10-06

| Lệnh | Kết quả |
| --- | --- |
| `mvn verify -Ppostgres-it` — phase test | 58/58 đạt, gồm 56 test cũ và 2 test hồi quy decoder; 0 failures, 0 errors, 0 skipped |
| `mvn verify -Ppostgres-it` — PostgreSQL | 17/17 đạt; 0 failures, 0 errors, 0 skipped |
| Tổng kết | **75/75 đạt — BUILD SUCCESS**, hoàn tất lúc 15:26:16 UTC+7 |

Sau khi Ân cho phép, chỉ sửa phân loại exception trong `CustomJwtDecoder`: kết quả introspect không hợp lệ được chuyển thành `BadJwtException` ngoài khối catch chung, để Spring Security trả HTTP 401. Lỗi dịch vụ introspect vẫn giữ phân loại `JwtException` như trước. Thêm hai test hồi quy riêng; không sửa kỳ vọng của các test đã có và không sửa signup.

Lịch sử trước sửa: lần chạy lúc 15:19:27 UTC+7 có 14/17 test PostgreSQL đạt, ba test JWT sai chữ ký/hết hạn/đã thu hồi bị lỗi. Cả ba đã đạt trong lần chạy mới. Không truy cập database Supabase hoặc AI thật trong các lần chạy này. Số liệu trên là kết quả của working tree tại thời điểm chạy, không phải cam kết cho các commit tương lai hoặc cho database Supabase đang dùng.

## 1. Mục tiêu và giới hạn

Bộ test kiểm tra code Question CRUD, script migration đang có trong Git và PostgreSQL tạm. Không đọc `application.yaml`, không dùng `DB_URL`/mật khẩu Supabase, không gọi AI và không chạy migration lên project dùng chung.

Kết quả xanh không có nghĩa mọi cấu hình/dữ liệu trên Supabase đã được xác minh. Bộ test PostgreSQL dùng Docker PostgreSQL 17 có pgvector; không mô phỏng Supabase Gateway, Storage, Auth hoặc quyền Dashboard. Luồng API dùng MockMvc (DispatcherServlet và filter thật), không mở server HTTP/TLS.

## 2. Chuẩn bị cho mỗi thành viên

1. Pull cùng commit/nhánh đã thống nhất; không chạy lại migration trên Supabase chung.
2. Cài JDK 21 và Maven, mở Terminal tại `avies-backend`.
3. Kiểm tra `java -version` và `mvn -version`; Maven phải dùng JDK 21.
4. Với PostgreSQL test, mở Docker Desktop ở chế độ Linux containers. Chạy `docker version` và xác nhận có cả Client lẫn Server.
5. Lần đầu cần mạng để tải dependency và image `pgvector/pgvector:pg17`, được cố định digest trong `QuestionPostgresConfig` (PostgreSQL 17.11 ở lần kiểm tra này). Docker phải có đủ tài nguyên để chạy PostgreSQL và container dọn dẹp Ryuk. Đây là cùng major version 17, không phải bản sao chính xác PostgreSQL 17.6/Supabase đã backup.

Không cần tạo tài khoản/môn học test trong Supabase, không copy backup thật vào test và không cung cấp API key.

## 3. Lệnh chạy

### Bộ test nhanh, không cần Docker

```powershell
mvn test
```

Chạy các test Question hiện có với MockMvc/H2. Test dùng database và AI bên ngoài được loại khỏi bộ mặc định bằng tag `external-services`, đồng thời có điều kiện opt-in ở class để tránh vô tình chạy qua IDE.

### Bộ kiểm thử đầy đủ cho Question trên PostgreSQL

```powershell
mvn verify -Ppostgres-it
```

Lệnh này chạy bộ test nhanh rồi chạy `QuestionPostgresIT` bằng Maven Failsafe. Không dùng riêng `mvn test -Ppostgres-it`: phase `test` chưa chạy phần integration-test/verify.

- Testcontainers tạo database tạm, tự chọn cổng và hủy container khi kết thúc.
- Datasource chỉ lấy URL từ container do test tạo, không có tùy chọn trỏ sang database ngoài.
- Thiếu Docker hoặc không tải được image sẽ làm build thất bại, không âm thầm bỏ qua PostgreSQL test.
- Hibernate dùng `validate`, không dùng `update`/`create` để che lỗi migration.
- Không bật container reuse cho bộ test này.
- Không dùng `-DskipTests` hoặc `-Dmaven.test.skip=true` khi xác nhận kết quả.

Chỉ chạy lại phần PostgreSQL khi cần chẩn đoán:

```powershell
mvn verify -Ppostgres-it "-Dit.test=QuestionPostgresIT"
```

### Test Supabase/AI cũ: không cần cho việc xác nhận Question

`AviesBackendApplicationTests` là test dịch vụ bên ngoài, không phải test PostgreSQL cô lập. Chỉ người có thẩm quyền mới chạy khi đã cấu hình đúng môi trường riêng, hiểu rằng sẽ đọc database và gọi AI có thể tính phí:

```powershell
mvn test -Pexternal-services "-DallowExternalServices=true"
```

Không dùng lệnh này trong hướng dẫn test thông thường của nhóm hoặc trên CI mặc định. Không truyền bí mật trong command line/Git. Profile và cờ xác nhận là hai lớp opt-in; thiếu cờ, class sẽ bị bỏ qua, không được tính là đã kiểm chứng external services.

## 4. Nội dung kiểm tra PostgreSQL

| Nhóm | Nội dung |
| --- | --- |
| CRUD xuyên suốt | Signin thật, ký/giải mã JWT thật, controller/service/repository thật, kiểm tra dữ liệu đã commit và audit |
| Phân quyền | Admin, lecturer đúng môn, lecturer ngoài môn, chủ sở hữu, người cùng môn, student, thu hồi role trong database |
| JWT | Token bị sửa chữ ký, hết hạn và đã logout/blacklist bị từ chối bằng HTTP 401; lỗi dịch vụ introspect không bị phân loại nhầm thành token không hợp lệ |
| Đồng thời | Hai PATCH cùng version chỉ một lần thành công; hai DELETE chỉ ghi một sự kiện xóa |
| Transaction | Database từ chối ghi audit phải rollback cả câu hỏi và topics |
| Bài thi | Chặn sửa/xóa câu hỏi đã được tham chiếu |
| Migration | Đọc nguyên script `docs/migrations/20261005_question_crud.sql`, gồm `BEGIN`/`COMMIT` và `DO $$` |
| Dữ liệu cũ | Giữ nội dung; backfill AI/APPROVED có căn cứ; giữ null khi chưa xác minh; chạy lại không ghi đè phân loại thủ công |
| Migration lỗi | Bloom ngoài MVP làm migration thất bại, không nâng cấp schema một phần |
| Database | Kiểm tra số cột, constraint, index; thử vi phạm CHECK/UNIQUE/FK |
| RLS | Dùng role `NOSUPERUSER NOBYPASSRLS` được cấp quyền bảng để kiểm tra RLS thật, không chỉ kiểm tra cờ |
| Khởi tạo mới | Chạy SQL khởi tạo đầy đủ trên database tạm mới và kiểm tra nền móng Question |

Fixture `src/test/resources/question/legacy-question-schema.sql` là schema cũ tối thiểu cho Question/xác thực, không phải backup đầy đủ của hệ thống. Bảng `student_exam_questions` trong fixture chỉ mô phỏng tham chiếu tới câu hỏi; test không xác nhận toàn bộ nghiệp vụ Exam Session.

## 5. Đọc kết quả và chẩn đoán

- Yêu cầu `BUILD SUCCESS`, không có failures/errors và không có PostgreSQL test bị skipped.
- Báo cáo test nhanh: `target/surefire-reports/`.
- Báo cáo PostgreSQL: `target/failsafe-reports/`, đặc biệt `com.avies.backend.question.QuestionPostgresIT.txt` và `failsafe-summary.xml`.
- `SECRET_SQL_DETAIL` là lỗi giả lập trong test che giấu thông tin, không phải lỗi tự phát của ứng dụng.
- Một số test cố ý gây lỗi CHECK/FK/RLS để xác nhận database từ chối dữ liệu. Đọc kết quả assertions cuối cùng, không chỉ tìm từ ERROR trong log.
- Mockito được nạp bằng `-javaagent` cho cả Surefire/Failsafe. Cảnh báo JVM về class-data sharing có thể vẫn xuất hiện; đây không phải cảnh báo Mockito tự attach.
- Docker connection refused/permission denied: kiểm tra Docker Desktop, Linux engine và quyền truy cập Docker; không đổi datasource sang Supabase để chạy cho qua.

## 6. Kiểm tra database Supabase đang dùng

Sau khi local test đạt, người phụ trách chạy riêng script chỉ đọc [question_post_migration_check.sql](../migrations/question_post_migration_check.sql) trong đúng Supabase project. Không đưa credentials vào repository. Lưu kết quả kiểm tra và commit đã test vào nhật ký migration nếu cần.

Script kiểm tra schema không thay thế kiểm thử API trên môi trường triển khai. Việc tạo/sửa/xóa dữ liệu test trên Supabase chung phải được nhóm cho phép riêng; chưa nằm trong bộ test tự động này.

## 7. Rủi ro đã biết, chưa xử lý trong đợt này

- API signup hiện nhận `roleCode` từ client, bao gồm `ADMIN`. Đây là rủi ro tự nâng quyền. Ân đã xác nhận để việc sửa đăng ký sang đợt riêng; bộ test Question đạt không có nghĩa luồng đăng ký đã an toàn.
- SQL khởi tạo `AIVES_DB_POSTGRESQL_FINAL.sql` hiện chưa khai báo bảng `invalidated_tokens` mà xác thực đang dùng. Fixture test có bảng này theo schema cũ; test SQL khởi tạo chỉ xác nhận nền móng Question, chưa xác nhận có thể bootstrap toàn bộ ứng dụng/xác thực từ file đó. Database Supabase đã backup có bảng này; phát hiện không có nghĩa migration vừa chạy đã xóa bảng.
- Chưa kiểm chứng RLS policy/quyền Data API thực tế của Supabase bằng bộ test Docker; cần kiểm tra cấu hình môi trường triển khai riêng.
- Không cam kết database “hoàn toàn ổn định” chỉ từ một lần test: kết quả chỉ chứng minh các kịch bản đã chạy trên commit/môi trường tương ứng.

## Tham khảo cấu hình công cụ

- [Testcontainers](https://java.testcontainers.org/): database tạm và quản lý vòng đời container.
- [Mockito Java agent](https://github.com/mockito/mockito/blob/main/mockito-core/src/main/java/org/mockito/Mockito.java): nạp agent qua Maven để tránh cơ chế tự attach.
