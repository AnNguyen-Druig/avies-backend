# Nhật ký migration database

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
