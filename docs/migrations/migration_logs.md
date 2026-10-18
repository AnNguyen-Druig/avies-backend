# Nhật ký migration database

## 2026-10-18 — Chuẩn bị Feature 2 / P0.3 (CHƯA áp dụng Supabase)

- Tạo file `20261016_feature2_exam_foundation.sql` theo tên đã duyệt; ngày trong tên file không phải ngày thực thi.
- Thêm sáu cột lịch nullable, bốn CHECK và UNIQUE `(session_id, slot_order)` thông thường; không backfill lịch hoặc thay đổi 20 constraint gốc.
- Tạo verifier chỉ đọc `feature2_post_migration_check.sql`, đồng bộ SQL fresh install và mapping JPA.
- Chi tiết safety, mapping, test và checklist review: [Feature 2 exam foundation](../business_rules/feature2-exam-foundation.md).
- Chỉ thực thi trên PostgreSQL Docker tạm để kiểm thử. Chưa có người/giờ thực thi hoặc kết quả kiểm tra Supabase cho migration này.
- Kết quả kiểm thử file ngày 18/10/2026: `mvn clean verify -Ppostgres-it` BUILD SUCCESS, 207 test đạt (57 test P0.3), không failure/error/skipped. Đây là xác minh cô lập, không phải nhật ký triển khai Supabase.
- Người phụ trách chỉ ghi nhận triển khai sau backup, review metadata thực tế và chạy script được phê duyệt. Giữ nguyên lịch sử Question bên dưới.

## 2026-10-06 — Nền móng Question CRUD

### Thông tin thực hiện

| Mục | Giá trị |
| --- | --- |
| Người thực hiện migration | Ân |
| Thời điểm ghi nhật ký | 2026-10-06 14:53:24 +07:00 (Asia/Bangkok) |
| Ngày thực hiện migration | 2026-10-06; chưa ghi nhận giờ chạy chính xác |
| Nền tảng | Supabase Dashboard → SQL Editor |
| Project | AIVES — `zgdllubbmtqvzfsmezpg` |
| Database / schema | `postgres` / `aives` |
| Role chạy SQL | `postgres` |
| Phương pháp | Chạy thủ công toàn bộ script trong transaction `BEGIN` → `COMMIT` |
| File migration | [20261005_question_crud.sql](20261005_question_crud.sql) |
| SQL khởi tạo tương ứng | [AIVES_DB_POSTGRESQL_FINAL.sql](../database/AIVES_DB_POSTGRESQL_FINAL.sql) |
| Commit liên quan | `956223d` — Question CRUD và migration; `4ecca0e` — bổ sung RLS |
| Kết quả thực thi | SQL Editor hiển thị `Success. No rows returned` theo ảnh Ân cung cấp |

Thời điểm ghi nhật ký không phải thời điểm thực thi migration. Kết quả dưới đây dựa trên script trong repository, file backup và ảnh kết quả do Ân cung cấp; chưa có bước kiểm tra trực tiếp database từ công cụ của trợ lý.

### Sao lưu và kiểm tra trước migration

- Đã xuất backup schema `aives` kèm dữ liệu bằng IntelliJ → Export with pg_dump.
- File backup: `D:\SEM7\SWD392\Project-Assignment\backup_database_schema\postgres_aws_0_ap_northeast_2_pooler_supabase_com-2026_10_06_14_15_06-dump.sql`.
- Log export: bắt đầu lúc `14:15:06`, kết thúc lúc `14:15:20` ngày `2026-10-06`; hiển thị `pg_dump process finished`.
- File có dung lượng 115.330 byte, chứa 14 bảng, 14 khối `COPY` đóng đầy đủ và dấu kết thúc `PostgreSQL database dump complete`.
- PostgreSQL nguồn: `17.6`; công cụ pg_dump: `18.4`.
- Precheck xác nhận database/user đều là `postgres`; các bảng `questions`, `users`, `subjects`, `student_exam_questions` trong schema `aives` đều tồn tại.
- Số câu hỏi trước migration: `0`; truy vấn tìm Bloom ngoài bốn mức MVP không trả về bản ghi.
- Chưa thử restore. Backup chỉ bao gồm schema `aives`, không phải toàn bộ project Supabase; khi khôi phục sang database mới cần chuẩn bị dependency `public.vector` tương ứng.
- Backup chứa dữ liệu nhạy cảm, không đưa vào Git.

### Thay đổi được áp dụng theo script

1. Bổ sung tám cột vào `aives.questions`: `reference_answer`, `answer_time_limit_seconds`, `source_type`, `status`, `updated_by`, `updated_at`, `archived_at`, `version`.
2. Thiết lập mặc định: `source_type = MANUAL`, `status = DRAFT`, `is_ai_generated = false`, `version = 0`, `updated_at = CURRENT_TIMESTAMP`; `updated_at` và `version` không được null.
3. Giữ hai cờ cũ `is_ai_generated`, `is_approved`. Logic backfill chỉ suy ra nguồn `AI` hoặc trạng thái `APPROVED` khi cờ tương ứng là true; không tự suy diễn các trường hợp khác. Trước migration không có câu hỏi nên không có dữ liệu câu hỏi cần backfill.
4. Bổ sung năm CHECK constraint: `ck_question_bloom_mvp`, `ck_question_answer_seconds`, `ck_question_source`, `ck_question_status`, `ck_question_version`.
5. Tạo ba bảng:
   - `aives.question_topics`: lưu nhiều nhãn chủ đề cho một câu hỏi, chống trùng chủ đề không phân biệt hoa/thường trong cùng câu hỏi.
   - `aives.lecturer_subject_assignments`: phân công giảng viên–môn học, không cho trùng cặp `(lecturer_id, subject_id)`.
   - `aives.question_audit_logs`: nhật ký tạo/sửa/xóa; `question_id` không có khóa ngoại để giữ nhật ký sau khi xóa câu hỏi.
6. Bổ sung các index: `uq_question_topics_normalized`, `idx_assignment_subject`, `idx_question_audit_question`, `idx_question_subject_status`, `idx_question_creator`.
7. Bật RLS cho cả ba bảng mới trước `COMMIT`. Script không tạo policy cho phép client truy cập trực tiếp.
8. Đồng bộ các lệnh bật RLS vào file SQL khởi tạo trong repository.

### Sự cố và cách xử lý

- **Cảnh báo tạo bảng chưa bật RLS:** bổ sung ba lệnh `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` vào migration trước `COMMIT`; ảnh lần chạy sau hiển thị thành công.
- **Phân công giảng viên `11` vào môn `3` bị lỗi `23503`:** khóa ngoại từ chối vì `subjects.id = 3` không tồn tại. Ảnh kiểm tra xác nhận user `11` có role `LECTURER`; các môn hiện có mang ID `1`, `2`, `4`, `5`. Hướng xử lý là chọn ID của môn thực sự được phân công hoặc tạo môn qua nghiệp vụ phù hợp trước. Chưa nhận được kết quả xác nhận lần phân công lại thành công; không bỏ khóa ngoại hoặc tắt RLS để xử lý lỗi này.

### Trạng thái xác minh và bàn giao

- Đã xác nhận: backup được xuất; precheck đạt; SQL Editor báo thực thi migration thành công.
- Chưa ghi nhận kết quả đầy đủ của bước kiểm tra sau migration: cần đối chiếu 16 cột của `questions`, ba bảng mới, các constraint/index và trạng thái RLS thực tế.
- Chưa ghi nhận kết quả kiểm thử API Question CRUD trên database Supabase sau migration; không coi thông báo chạy SQL thành công là kết quả kiểm thử API.
- Thành viên dùng cùng database Supabase không chạy lại migration; lấy phiên bản code đã thống nhất, refresh datasource trong IDE và kiểm tra cấu hình kết nối/schema `aives`.
- Sau khi chốt phiên bản đã triển khai, thay đổi schema tiếp theo phải được ghi trong file migration mới. Không chạy SQL khởi tạo hoặc file backup lên database dùng chung để đồng bộ.
- Bình luận đầu file migration còn ghi trạng thái đề xuất; nhật ký này ghi nhận việc Ân đã thực thi. Không sửa lịch sử SQL đã áp dụng chỉ để cập nhật trạng thái triển khai.


## 2026-10-18 — Feature 2 Exam Foundation Migration

### 1. Thông tin triển khai

| Mục | Giá trị                                           |
|---|---------------------------------------------------|
| Project | AIVES — SWD392                                    |
| Ngày triển khai | 2026-10-18                                        |
| Giờ triển khai | 12h AM                                            |
| Người thực hiện | Nguyễn Trần Đạt Ân                                |
| Nền tảng | Supabase Dashboard — SQL Editor                   |
| Database | `postgres`                                        |
| Schema | `aives`                                           |
| PostgreSQL version | 17.6                                              |
| Branch Backend | `dev/An`                                          |
| Migration file | `20261016_feature2_exam_foundation.sql`           |
| Verification file | `feature2_post_migration_check.sql`               |
| Phương pháp | Manual SQL migration với transaction BEGIN/COMMIT |
| Kết quả | SUCCESS                                           |

### 2. Backup trước migration

- Đã export schema `aives` kèm dữ liệu bằng IntelliJ và `pg_dump`.
- Backup file: `postgres_aws_0_ap_northeast_2_pooler_supabase_com-2026_10_18_22_06_56-dump.sql`.
- Định dạng: PostgreSQL Custom Archive, dù file có phần mở rộng `.sql`.
- File backup được lưu riêng, không commit lên Git.
- Đã kiểm tra archive theo quy trình pre-deployment.
- Chưa thực hiện thử restore hoàn chỉnh; không xem backup là đã được xác minh khả năng khôi phục đầy đủ.

### 3. Database changes

Bổ sung 6 columns:

**aives.exam_sessions**
- `scheduled_start_at TIMESTAMPTZ NULL`
- `scheduled_end_at TIMESTAMPTZ NULL`
- `slot_duration_minutes INTEGER NULL`

**aives.student_exams**
- `slot_order INTEGER NULL`
- `scheduled_start_at TIMESTAMPTZ NULL`
- `scheduled_end_at TIMESTAMPTZ NULL`

Bổ sung 5 constraints:

- `ck_exam_session_schedule`
- `ck_exam_session_slot_duration`
- `ck_student_exam_schedule`
- `ck_student_exam_slot_order`
- `uq_student_exam_slot_order`

Không tạo bảng mới, không xóa cột và không chủ động thay đổi dữ liệu cũ.

### 4. Kết quả Post-migration Verification

| Tiêu chí | Kết quả |
|---|---|
| Migration SQL | SUCCESS |
| Verification checks | 66/66 PASS |
| Tổng columns của 3 bảng | 28 |
| Tổng constraints của 3 bảng | 25 |
| `exam_sessions` records | 1 → 1 |
| `student_exams` records | 1 → 1 |
| `student_exam_questions` records | 2 → 2 |
| Dữ liệu lịch mới của bản ghi cũ | [Xác nhận NULL sau bước kiểm tra cuối] |

Các kết quả trên được xác nhận thông qua Supabase SQL Editor.

### 5. Backend Validation

- Đã kiểm thử bằng JDK 21.
- Maven build và automated tests thành công.
- Testcontainers sử dụng PostgreSQL độc lập.
- 207 automated tests PASS theo kết quả kiểm thử đã báo cáo.
- Chưa thực hiện kiểm thử Feature 2 CRUD API hoặc luồng vấn đáp trên Supabase thật vì các chức năng này thuộc những bước triển khai tiếp theo.

### 6. Trạng thái và lưu ý

**Status: MIGRATION APPLIED — POSTCHECK PASSED**

- Database Supabase đã áp dụng schema Feature 2 foundation.
- Không cần chạy lại migration trên database dùng chung.
- Các thành viên cần đồng bộ phiên bản Backend chứa Entity, Enum và Repository tương ứng.
- Giữ nguyên cấu hình Hibernate `ddl-auto: none`.
- Chưa triển khai Feature 2 CRUD hoặc Feature 3.

**Next step:** P0.6 — Backend verification sau khi cập nhật database.