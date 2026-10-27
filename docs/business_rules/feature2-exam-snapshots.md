# Feature 2 — Rubric cấp môn và snapshot bài thi

Ngày chuẩn bị: 25/10/2026. Nhánh: `dev/An`, baseline `88c3a68`.
Phạm vi: backend, migration/SQL/diagram và test cô lập. Chưa áp dụng lên database dùng chung, chưa sửa frontend.

## 1. Thiết kế hiện hành

- `Subject` có tối đa một rubric hiện hành qua `rubrics.subject_id UNIQUE`.
- `Question.referenceAnswer` vẫn là đáp án riêng; AI generation không tạo rubric mới cho từng câu.
- Rubric của môn có thể thay đổi. Vì vậy FK tới câu hỏi hoặc rubric nguồn KHÔNG thay thế được snapshot lịch sử.
- `ExamSession` giữ một rubric đã chốt cho cả phiên; mỗi `StudentExamQuestion` giữ bản chụp câu đã gán.
- Chưa có API Start Exam. `ExamQuestionSelectionService.selectForStart` là SPI nội bộ, chỉ dùng trong transaction ghi của caller tin cậy. Không gọi từ controller nếu chưa kiểm tra quyền/lịch thi.

## 2. Thời điểm và dữ liệu chốt

| Nơi lưu | Trường | Ý nghĩa |
|---|---|---|
| exam_sessions | rubric_snapshot TEXT | JSON schemaVersion=1: ID rubric nguồn, môn, tiêu đề, mô tả, thời điểm cập nhật nguồn, toàn bộ tiêu chí |
| exam_sessions | rubric_snapshot_at | Thời điểm phiên chọn bộ câu đầu tiên thành công |
| student_exam_questions | snapshot_content | Nội dung câu khi gán |
| student_exam_questions | snapshot_reference_answer | Đáp án riêng lúc gán; giữ null nếu chưa có |
| student_exam_questions | snapshot_bloom_level | Bloom lúc gán, hỗ trợ sáu giá trị enum hiện hành |
| student_exam_questions | snapshot_answer_time_limit_seconds | Giới hạn gốc; null vẫn hợp lệ |
| student_exam_questions | snapshot_question_version | Version nguồn để truy xuất, không dùng thay nội dung |
| student_exam_questions | snapshot_captured_at | Thời điểm chốt câu |

JSON dùng TEXT để không phụ thuộc Hibernate JSON converter; `ExamRubricSnapshot` là hợp đồng typed nội bộ. PostgreSQL CHECK xác nhận payload là JSON object schemaVersion=1 với mảng criteria; service kiểm tra đầy đủ nội dung và tổng điểm.
Tiêu chí giữ ID nguồn, mô tả, điểm tối đa BigDecimal, thứ tự và cờ mandatory. Tổng đúng 10, điểm dương tối đa hai số thập phân, ID và thứ tự không trùng. Cờ mandatory được lưu nhưng MVP KHÔNG tự đánh trượt câu.
Không có FK tới rubric/tiêu chí nguồn trong payload: sửa hoặc xóa nguồn không được làm mất thang chấm cũ.

## 3. Transaction, khóa và idempotency

1. Bắt buộc transaction ghi có sẵn; không tự mở transaction selection độc lập.
2. Giữ giao thức khóa lecturer → subject → session → student exam → liên kết đã lưu.
3. Nếu đã có bộ câu: kiểm tra đủ số lượng/thứ tự/distinct và snapshot; trả nguyên ID, không random và không đọc lại rubric hiện hành.
4. Nếu phiên chưa chốt rubric nhưng đã có câu lịch sử hoặc lượt thi có bằng chứng sử dụng: trả 409/1506. Không tự dựng lại rubric quá khứ.
5. Với phiên mới: khóa rubric của môn FOR UPDATE NOWAIT rồi criteria FOR SHARE NOWAIT; đọc scalar mới và chốt đầy đủ rubric. Chỉ một rubric, không kỳ vọng một rubric cho mỗi câu.
6. Chọn đúng N câu APPROVED, chưa archive, đúng môn, Bloom/nội dung/version hợp lệ. Thuật toán chống lặp/Bloom giữ nguyên. Rubric đã chốt được áp dụng cho mọi sinh viên, kể cả sau khi nguồn bị sửa/xóa.
7. Khóa câu theo ID tăng dần FOR SHARE NOWAIT, đọc lại scalar và so fingerprint, gồm cả đáp án và giới hạn trả lời. Không lấy entity cache cũ để snapshot.
8. Ghi đúng N liên kết kèm snapshot. Rubric và câu cùng transaction caller; lỗi bất kỳ làm rollback toàn bộ.

Khi rubric đã chốt, không đổi môn, maxMainQuestions hoặc maxFollowUps. PATCH gửi lại đúng giá trị vẫn hợp lệ nếu các điều kiện CRUD khác đáp ứng. Sửa tiêu đề không thay snapshot.
Lịch và slot vẫn theo quy tắc CRUD/phân công đã có. Không nới quyền, không thay trạng thái hoặc tự khởi động bài thi.

## 4. Dữ liệu lịch sử và bảo mật

- `ExamSnapshotService.loadForGrading(studentExamId)` chỉ đọc snapshot cho Feature 3/4; không fallback sang `Question.content/referenceAnswer` hoặc rubric hiện hành.
- Reader là SPI nội bộ, không phải API công khai. Caller phải xác thực, kiểm tra role/ownership và quyền xem/chấm bài. Không gắn trực tiếp DTO grading vào API sinh viên.
- `ExamGradingContext` chứa đáp án. Feature 3 phải chọn riêng các trường đề được phép gửi; Feature 4 dùng đầy đủ rubric/câu/đáp án đã chốt cùng transcript. AI chỉ gợi ý, giảng viên chốt điểm.
- Các controller hiện tại vẫn trả DTO cũ, không thêm payload snapshot/đáp án. `SelectedMainQuestion` chỉ có ID liên kết, ID câu và thứ tự.
- Trigger chặn sửa/xóa giá trị snapshot đã chốt bằng UPDATE, kể cả raw SQL. Trigger cũng bảo vệ câu nguồn/thứ tự liên kết và cấu hình đề đã chốt. Không bảo vệ trước DBA cố tình tắt trigger hoặc xóa toàn bộ dữ liệu; quy tắc không xóa bài đã dùng của service và FK vẫn phải được giữ.
- Đáp án/thời lượng null có thể hợp lệ. Không nhầm với legacy: snapshot_captured_at, nội dung, Bloom, version phải có; rubric phải có payload hợp lệ và thời điểm chốt.
- Mã mới 409/1506 `EXAM_SNAPSHOT_UNAVAILABLE` cho snapshot thiếu/hỏng/không hỗ trợ. Bộ thiếu/thừa/sai thứ tự vẫn 409/1502. Lỗi nguồn rubric trước chốt vẫn 409/1501. Không trả SQL hoặc nội dung chấm trong message.

## 5. Migration thủ công — chỉ người phụ trách được thực hiện

1. Dừng ghi, kiểm tra đúng project/schema, sao lưu schema VÀ dữ liệu, lưu commit backend tương ứng.
2. Xác minh foundation 20261016 và rubric cấp môn đã được triển khai. KHÔNG chạy lại `20261022_rubric_subject_migration.sql` trên database đã chuyển đổi: file cũ có TRUNCATE dữ liệu rubric và không phải migration lặp an toàn. Task này không sửa migration cũ.
3. Review `docs/migrations/20261025_feature2_exam_snapshots.sql`. Chỉ thêm tám cột nullable, hai CHECK và hai trigger/function; không xóa bảng/cột, không backfill.
4. Sau khi người phụ trách cho phép triển khai, chạy toàn bộ migration một lần trong SQL Editor. Trước bước này, thay đổi chỉ tồn tại trong Git và container test.
5. Chạy `feature2_snapshot_postcheck.sql`, đối chiếu counts trước/sau. Nhóm schema phải PASS; danh sách legacy cần rà riêng. Không coi schema PASS là đã có snapshot cho bài cũ.
6. Với bài legacy đã dùng: giữ nguyên, không tự chấm lại bằng căn cứ hiện tại. Nếu cần bổ sung lịch sử phải có dữ liệu đáng tin cậy và kế hoạch riêng được duyệt. Không tự xóa bài để né lỗi.
7. Deploy backend sau migration; giữ `ddl-auto: none`. Thành viên pull cùng commit, refresh datasource, không tự chạy lại migration rubric cũ.
8. Cập nhật nhật ký với thời điểm/người thực hiện/kết quả thật. Nhật ký chuẩn bị không được đổi thành “đã áp dụng” khi chưa kiểm tra database dùng chung.

SQL khởi tạo fresh install có block snapshot đồng bộ với migration; không chạy toàn bộ file khởi tạo lên database đã có dữ liệu thay cho migration.
Diagram: trang `Feature 2 - Immutable exam snapshots` trong `All_Diagram.drawio`; các trang foundation cũ là tài liệu lịch sử.

## 6. Kiểm thử và bước tiếp theo

- Chạy JDK 21: `./mvnw.cmd verify -Ppostgres-it -B -ntp` khi Docker Desktop Linux containers hoạt động.
- Không dùng profile external-services, .env, URL Supabase hoặc AI thật. Các test chạy migration trong PostgreSQL container tạm và tự dọn.
- Bao phủ: rubric dùng chung, khóa thật, retry, rollback, thay đáp án trước khóa, sửa/xóa nguồn sau commit, immutable snapshot, dữ liệu legacy, migration lặp và CRUD không lộ đáp án.
- Sau review BE và migration thủ công: kiểm tra FE tích hợp CRUD/phân công hiện có. Kiểm thử chọn câu/chốt snapshot qua browser cần workflow/API Start được thiết kế và triển khai ở bước tiếp theo; không giả định endpoint đó đã có trong task này.

### Kết quả xác minh 25/10/2026

- JDK 21.0.10, PostgreSQL 17.11/pgvector trong Testcontainers, image đã khóa digest.
- Lệnh: `./mvnw.cmd clean verify -Ppostgres-it -B -ntp`.
- Kết thúc 09:58:12 +07:00: BUILD SUCCESS; 326 Surefire + 220 Failsafe = 546 test, 0 failure/error/skipped.
- `ExamSnapshotPostgresIT`: 29 test, gồm snapshot sáu Bloom, immutable raw UPDATE, source mutation/deletion, legacy, rollback, migration lặp, postcheck và kiểm tra CRUD không lộ đáp án.
- `git diff --check` đạt. XML drawio đọc hợp lệ, có sáu trang; không tuyên bố đã kiểm tra hiển thị bằng ứng dụng draw.io.
- Không chạy backend live, profile external-services, Supabase hoặc AI thật. Kết quả trên không phải xác nhận database dùng chung đã được migration.
