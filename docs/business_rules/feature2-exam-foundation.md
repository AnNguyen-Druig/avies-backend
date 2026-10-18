# Feature 2 / P0.3 — Nền móng database, entity và repository

Ngày lập: **18/10/2026 (Asia/Bangkok)**. Nhánh triển khai: `dev/An`.

## A. Đánh giá hiện trạng

- Worktree sạch lúc bắt đầu. Repository chưa có migration Feature 2 tương đương, entity thi, enum thi hoặc repository thi.
- SQL khởi tạo và Physical ERD đã có `exam_sessions`, `student_exams`, `student_exam_questions`; không tạo bảng mới hay bảng nối trùng chức năng.
- Baseline P0.1 do Ân cung cấp xác nhận **20 constraint**: 6 trên phiên thi, 8 trên bài thi sinh viên và 6 trên liên kết câu hỏi. Không suy ra các bảng rỗng hoặc metadata cột live giống hoàn toàn SQL repository.
- Migration additive tương thích baseline đã cung cấp, với điều kiện metadata/index thực tế đạt guard. Guard kiểm tra cả các cột/index theo SQL repository; đó là tiền điều kiện cần kiểm tra trước triển khai, chưa phải kết quả audit Supabase.
- Đã đọc SQL khởi tạo, migration/nhật ký/verifier Question, business rules Question/phân công, workflow Feature 1, mapping và test hiện tại. Giữ bốn trang gốc của `All_Diagram.drawio`, bổ sung trang `Feature 2 P0.3 - Proposed foundation` cho sáu thuộc tính lịch, constraint và FK. `Sequence-StateMachine_Diagram.drawio` giữ luồng/state cũ, P0.3 không thay đổi vòng đời.

## B. File tạo mới

| Đường dẫn tương đối từ backend | Mục đích |
| --- | --- |
| `docs/migrations/20261016_feature2_exam_foundation.sql` | Migration có transaction và guard schema drift |
| `docs/migrations/feature2_post_migration_check.sql` | Verifier chỉ đọc, PASS/FAIL từng invariant |
| `src/main/java/com/avies/backend/entity/ExamSession.java` | Mapping phiên thi |
| `src/main/java/com/avies/backend/entity/StudentExam.java` | Mapping bài thi sinh viên |
| `src/main/java/com/avies/backend/entity/StudentExamQuestion.java` | Mapping câu được gán vào bài thi |
| `src/main/java/com/avies/backend/entity/enums/ExamSessionStatus.java` | UPCOMING / ONGOING / COMPLETED |
| `src/main/java/com/avies/backend/entity/enums/StudentExamStatus.java` | ASSIGNED / IN_PROGRESS / COMPLETED |
| `src/main/java/com/avies/backend/repository/ExamSessionRepository.java` | Truy vấn phiên thi có phân trang |
| `src/main/java/com/avies/backend/repository/StudentExamRepository.java` | Truy vấn bài thi và thứ tự lịch |
| `src/main/java/com/avies/backend/repository/StudentExamQuestionRepository.java` | Truy vấn câu đã gán/tham chiếu |
| `src/test/java/com/avies/backend/exam/ExamFoundationPostgresConfig.java` | Context JPA validate trên Docker PostgreSQL tạm |
| `src/test/java/com/avies/backend/exam/ExamFoundationPostgresIT.java` | Test mapping, FK, CHECK, UNIQUE, thứ tự và tương thích |
| `src/test/java/com/avies/backend/exam/Feature2MigrationPostgresIT.java` | Test migration schema cũ, fresh install, drift và rollback |
| `src/test/resources/exam/legacy-exam-schema.sql` | Baseline thi trước Feature 2 cùng dữ liệu giả không có lịch |
| `docs/business_rules/feature2-exam-foundation.md` | Báo cáo và hướng dẫn bàn giao này |

Tên file migration theo yêu cầu `20261016`; ngày tạo file là 18/10/2026. Tên file không chứng minh thời điểm triển khai.

## C. File sửa

| File | Lý do |
| --- | --- |
| `docs/database/AIVES_DB_POSTGRESQL_FINAL.sql` | Thêm sáu cột/năm constraint cho fresh installation, ghi rõ không dùng để nâng cấp DB cũ |
| `docs/diagrams/All_Diagram.drawio` | Bổ sung trang đề xuất P0.3; giữ nguyên nội dung bốn trang cũ |
| `docs/migrations/migration_logs.md` | Ghi mốc chuẩn bị file; không ghi nhận đã chạy Supabase |
| `src/test/java/com/avies/backend/question/QuestionPersistenceTestConfig.java` | Loại repository thi khỏi context H2 chỉ đăng ký entity Question |
| `src/test/java/com/avies/backend/question/QuestionPostgresConfig.java` | Loại repository thi khỏi fixture Question tối giản |
| `src/test/java/com/avies/backend/material/LearningMaterialAccessTestConfig.java` | Loại repository thi khỏi context chỉ đăng ký entity tài liệu |

Điều chỉnh test scan cần thiết vì ba repository mới cùng package với repository cũ; không thay đổi service/controller Feature 1. Không sửa `pom.xml`, cấu hình bảo mật, `application.yaml`, secrets, lịch sử migration đã áp dụng hoặc frontend.

## D. Migration và an toàn triển khai

### Sáu cột nullable, không default/backfill

| Bảng | Cột | Kiểu PostgreSQL | Ý nghĩa |
| --- | --- | --- | --- |
| `aives.exam_sessions` | `scheduled_start_at` | TIMESTAMPTZ | Bắt đầu dự kiến cả phiên |
| `aives.exam_sessions` | `scheduled_end_at` | TIMESTAMPTZ | Kết thúc dự kiến cả phiên |
| `aives.exam_sessions` | `slot_duration_minutes` | INTEGER | Số phút dự kiến cho mỗi sinh viên |
| `aives.student_exams` | `slot_order` | INTEGER | Thứ tự thi trong phiên |
| `aives.student_exams` | `scheduled_start_at` | TIMESTAMPTZ | Bắt đầu dự kiến của sinh viên |
| `aives.student_exams` | `scheduled_end_at` | TIMESTAMPTZ | Kết thúc dự kiến của sinh viên |

### Năm constraint bổ sung

- `ck_exam_session_schedule`: hai timestamp cùng NULL hoặc cùng có giá trị và kết thúc lớn hơn bắt đầu.
- `ck_exam_session_slot_duration`: NULL hoặc số phút > 0.
- `ck_student_exam_schedule`: quy tắc cặp timestamp tương tự phiên thi.
- `ck_student_exam_slot_order`: NULL hoặc thứ tự > 0.
- `uq_student_exam_slot_order`: UNIQUE thông thường `(session_id, slot_order)`, cho phép nhiều hàng có slot_order NULL. Unique B-tree index được PostgreSQL tạo cùng constraint.

Cặp lịch sử dụng các nhánh `IS NULL`/`IS NOT NULL` tường minh. CHECK không chấp nhận cặp chỉ có một timestamp nhờ SQL UNKNOWN. PostgreSQL mặc định cho phép nhiều NULL trong UNIQUE, phù hợp giữ dữ liệu cũ. Tham khảo [PostgreSQL 17 — Constraints](https://www.postgresql.org/docs/17/ddl-constraints.html).

Giữ nguyên toàn bộ 20 constraint gốc và sáu index truy vấn thi trong SQL repository. FK giữ `ON UPDATE CASCADE`; FK từ phiên tới user/môn và từ liên kết tới Question giữ `ON DELETE RESTRICT`; phiên → bài thi → liên kết giữ `ON DELETE CASCADE`. Không đổi grants, RLS/policies hoặc bảng xác thực.

### Guard và giới hạn

- Chạy trong BEGIN/COMMIT; `lock_timeout=5s`, `statement_timeout=60s`. Khóa ba bảng thi trong lúc kiểm tra và DDL. Chọn thời gian bảo trì vì CHECK/UNIQUE cần quét dữ liệu và có thể giữ khóa; timeout chỉ là giới hạn khởi đầu, không bảo đảm phù hợp mọi kích thước bảng.
- `IF NOT EXISTS` chỉ hỗ trợ chạy lại. Trước COMMIT, script đối chiếu cấu trúc cột, identity, PK/FK/UNIQUE/CHECK và index; có khác biệt thì exception và rollback toàn bộ lần chạy.
- CHECK được so sánh qua `pg_get_constraintdef`, chỉ bỏ khoảng trắng. Giữ dấu ngoặc/toán tử/casts; biểu thức tương đương nhưng biểu diễn khác vẫn có thể FAIL bảo thủ. Không tự nới guard khi chưa review metadata.
- Verifier xác minh `convalidated`, cột/cột tham chiếu, schema đích, hành vi update/delete, unique NULLS DISTINCT và index valid/ready. Không chỉ so tên.
- PostgreSQL yêu cầu phiên bản 15+ cho catalog `indnullsnotdistinct`; test thực hiện trên image PostgreSQL 17 đã khóa digest. Cần xác minh phiên bản thật trước triển khai.
- Không thay `student_exam_questions`, không chế tạo lịch từ `created_at`, `started_at` hoặc `completed_at`.
- P0.3 chưa kiểm tra role nghiệp vụ, quyền phân công, thời gian sinh viên nằm trong phiên, chồng lịch, sức chứa, trạng thái hợp lệ theo thời gian hoặc câu hỏi APPROVED. Các quy tắc này thuộc service sau P0.3; CHECK hiện có chỉ giới hạn tập giá trị.
- Cột nullable cho tương thích không có nghĩa API tạo phiên mới được bỏ lịch: service sau này phải bắt buộc lịch hợp lệ.
- Liên kết Question hiện là tham chiếu trực tiếp, chưa có snapshot. Trước chọn câu thực tế phải giữ kiểm tra `countExamReferences`; không mở sửa/xóa câu đã dùng mà chưa có thiết kế bảo toàn phiên bản.

### Trình tự dành cho người phụ trách migration

1. Review diff, xác nhận đúng project/schema và quyền chạy ALTER. Lấy metadata/cột/default, định nghĩa constraint/index và record counts thực tế. So sánh 20 constraint đã audit với guard trong file.
2. Sao lưu schema `aives` kèm dữ liệu bằng pg_dump; xác minh backup và khả năng restore trong môi trường riêng. Backup mới phải phản ánh dữ liệu trước lần triển khai này, không dùng backup tháng trước như bằng chứng đủ.
3. Dừng các luồng ghi phiên thi, thống nhất một người thực thi. Chạy TOÀN BỘ `20261016_feature2_exam_foundation.sql` một lần trong Supabase SQL Editor. Không chạy file khởi tạo toàn bộ.
4. Nếu lỗi, không chạy phần còn lại hoặc riêng COMMIT. ROLLBACK nếu transaction còn aborted, lưu lỗi/metadata để review; không tự đổi dữ liệu cũ hoặc bỏ constraint.
5. Chạy TOÀN BỘ `feature2_post_migration_check.sql`; mọi dòng phải PASS. So sánh record counts và snapshot dữ liệu/constraint trước–sau. Verifier một mình không chứng minh không mất dữ liệu trước đó nếu chưa có snapshot/counts.
6. Chỉ triển khai phiên bản backend sau khi schema đã đạt; giữ `ddl-auto: none`. Thành viên pull cùng commit, refresh datasource và dùng cùng môi trường database; không mỗi người chạy migration lại.
7. Ghi người/giờ chạy thực tế, backup, commit, kết quả và xác minh vào nhật ký sau khi thật sự triển khai. P0.3 hiện chưa có API thi để smoke test nghiệp vụ.

SQL chỉ đọc để thu thập baseline trước chạy (ghi lại kết quả):

```sql
SELECT current_database(), current_user, version();
SELECT 'exam_sessions' AS table_name, count(*) FROM aives.exam_sessions
UNION ALL SELECT 'student_exams', count(*) FROM aives.student_exams
UNION ALL SELECT 'student_exam_questions', count(*) FROM aives.student_exam_questions;
SELECT table_name, column_name, data_type, udt_name, is_nullable, column_default,
       character_maximum_length, numeric_precision, numeric_scale, is_identity
FROM information_schema.columns
WHERE table_schema = 'aives'
  AND table_name IN ('exam_sessions','student_exams','student_exam_questions')
ORDER BY table_name, ordinal_position;
SELECT conrelid::regclass AS table_name, conname, convalidated, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid IN ('aives.exam_sessions'::regclass, 'aives.student_exams'::regclass,
                  'aives.student_exam_questions'::regclass)
ORDER BY conrelid::regclass::text, conname;
SELECT tablename, indexname, indexdef FROM pg_indexes
WHERE schemaname = 'aives'
  AND tablename IN ('exam_sessions','student_exams','student_exam_questions');
```

Chưa COMMIT: PostgreSQL có thể rollback DDL trong transaction. Đã COMMIT: cần reverse migration được review hoặc restore backup; không tự xóa sáu cột vì chúng có thể đã nhận lịch mới. Khôi phục backup có thể mất dữ liệu ghi sau backup nên phải lập kế hoạch riêng. P0.3 không cung cấp rollback phá hủy tự động.

## E. Mapping entity và repository

Tất cả PK `Long`/IDENTITY; mọi association mới LAZY, đơn hướng, không cascade persist/remove. Timestamp dùng `OffsetDateTime`, điểm dùng `BigDecimal(5,2)`, enum lưu STRING. Javadoc tiếng Việt UTF-8 cho lớp/field/method mới. Entity không được trả trực tiếp qua API ở các bước sau.

| Entity → bảng | Field Java → cột |
| --- | --- |
| `ExamSession` → `aives.exam_sessions` | `id` → `id`; `lecturer` → `lecturer_id`; `subject` → `subject_id`; `title` → `title`; `status` → `status`; `maxMainQuestions` → `max_main_questions`; `maxFollowUps` → `max_follow_ups`; `createdAt` → `created_at`; `scheduledStartAt` → `scheduled_start_at`; `scheduledEndAt` → `scheduled_end_at`; `slotDurationMinutes` → `slot_duration_minutes` |
| `StudentExam` → `aives.student_exams` | `id` → `id`; `session` → `session_id`; `student` → `student_id`; `status` → `status`; `aiSuggestedScore` → `ai_suggested_score`; `finalScore` → `final_score`; `aiFeedback` → `ai_feedback`; `audioRecordUrl` → `audio_record_url`; `startedAt` → `started_at`; `completedAt` → `completed_at`; `slotOrder` → `slot_order`; `scheduledStartAt` → `scheduled_start_at`; `scheduledEndAt` → `scheduled_end_at` |
| `StudentExamQuestion` → `aives.student_exam_questions` | `id` → `id`; `studentExam` → `student_exam_id`; `question` → `question_id`; `questionOrder` → `question_order` |

| Relationship | FK đích |
| --- | --- |
| ExamSession.lecturer / ExamSession.subject | `users(id)` / `subjects(id)` |
| StudentExam.session / StudentExam.student | `exam_sessions(id)` / `users(id)` |
| StudentExamQuestion.studentExam / StudentExamQuestion.question | `student_exams(id)` / `questions(id)` |

| Repository | Hỗ trợ |
| --- | --- |
| `ExamSessionRepository` | `findById` kế thừa; lọc lecturer/subject có phân trang ID tăng dần; kiểm tra `existsByIdAndLecturerId` |
| `StudentExamRepository` | Lấy sinh viên theo phiên với slot tăng dần, NULL cuối và ID phá hòa; lọc theo sinh viên có phân trang; exists phiên/sinh viên và sinh viên/status |
| `StudentExamQuestionRepository` | Câu theo questionOrder/ID có phân trang; exists bài/câu; ID câu từng dùng trong phiên DISTINCT, tăng dần và phân trang |

Các repository mới chỉ dùng derived methods/JPQL, không có native SQL mới. `QuestionRepository.countExamReferences` giữ nguyên truy vấn cũ. Kiểm tra phân công vẫn dùng `LecturerSubjectAssignmentRepository` tại service tương lai, không sao chép sang repository thi.

## F. Biên dịch và kiểm thử

Yêu cầu JDK 21, Maven và Docker Desktop Linux engine. Testcontainers tự tạo/dọn PostgreSQL; không start các database container dự án khác. Không nạp `.env`/`application.yaml` trong context test mới, không gọi Supabase/AI.

```powershell
# Kiểm thử riêng P0.3; chạy cả initialize của Maven để có property Mockito agent.
mvn test-compile failsafe:integration-test failsafe:verify -Ppostgres-it "-Dit.test=ExamFoundationPostgresIT,Feature2MigrationPostgresIT"
# Biên dịch sạch, test nhanh và mọi nhóm PostgreSQL hồi quy.
mvn clean verify -Ppostgres-it
```

Kết quả thực thi ngày 18/10/2026 bằng JDK 21:

| Lệnh | Kết quả |
| --- | --- |
| Lệnh focused ở trên, trước khi bổ sung hai ca drift cuối | BUILD SUCCESS, 55 PostgreSQL test, 0 failure/error/skipped |
| `mvn clean verify -Ppostgres-it -B -ntp` | BUILD SUCCESS, biên dịch main/test và đóng gói thành công; **207 test**: 125 test nhanh + 82 PostgreSQL, 0 failure/error/skipped |

Trong lần chạy cuối: `ExamFoundationPostgresIT` đạt 39 test và `Feature2MigrationPostgresIT` đạt 18 test (**57 test P0.3**); 150 test hồi quy hiện có cũng đạt. Docker chạy PostgreSQL **17.11**, Hibernate `validate` đạt. Log full build nằm trong thư mục tạm của máy chạy (`aives-feature2-full-20261018.log`); báo cáo XML nằm tại `target/surefire-reports` và `target/failsafe-reports`, không commit output build.

Hai lỗi biên dịch test mới (dư dấu ngoặc và import `Role` trùng tên) được sửa trước lần build cuối. Không còn test lỗi hoặc vấn đề môi trường ngăn kiểm thử cô lập. Chưa chạy test external-services, deploy Supabase hoặc API thi (ngoài phạm vi P0.3).

- Entity/query test kiểm tra các trường/FK, enum, giờ dự kiến/thực tế, điểm lẻ, LAZY, phân trang/thứ tự, unique/null lịch cũ, constraints cũ và Question/Subject persistence.
- Migration test bắt đầu từ schema trước Feature 2 với bản ghi legacy và 20 constraint. Chạy migration lặp, so snapshot dữ liệu/constraint, kiểm tra fresh install và drift (type/null/default, CHECK yếu/chưa validate, UNIQUE sai/NULLS NOT DISTINCT, FK đổi action, index sai, lịch cũ không hợp lệ).
- Verifier được chạy bằng connection trong transaction READ ONLY; test cố ý phá CHECK/FK để xác nhận FAIL.
- DDL gây lỗi, CREATE/DROP database test chỉ diễn ra trong container sở hữu, tên database UUID có prefix được kiểm tra. Không nhận connection URL từ người dùng/môi trường.
- Profile `external-services` không chạy; kết quả test không xác nhận Supabase đã được migration, RLS/grants live hoặc chất lượng AI.

## G. Checklist review trước triển khai

- [ ] Xác nhận metadata cột/index và record counts live còn thiếu; 20 constraint audit không thay thế thông tin này.
- [ ] Review sáu cột, bốn CHECK, một UNIQUE và guard bảo thủ; không tự xếp lịch legacy.
- [ ] Xác nhận backup hiện tại, restore và thời gian bảo trì/lock phù hợp dữ liệu thật.
- [ ] Review quyền truy cập/RLS hiện tại giữ nguyên; test superuser trong Docker không thay kiểm thử role ứng dụng live.
- [ ] Chạy migration thủ công chỉ khi người phụ trách cho phép triển khai; cập nhật nhật ký bằng kết quả thật.
- [ ] Giữ Hibernate `none`; không chạy initializer hoặc profile external-services để đồng bộ DB dùng chung.
- [ ] Review code/test trước commit; không commit/push/merge tự động.
- [ ] Dừng ở P0.3, chờ review trước P0.4; chưa thêm API, service nghiệp vụ hoặc Feature 3/4.

## H. Git change summary

Git lúc bắt đầu: nhánh `dev/An`, worktree sạch. Lúc bàn giao: **15 file mới untracked, 6 file đã theo dõi được sửa**, liệt kê đầy đủ ở bảng B/C. Không stage/commit/push/merge.

`git status --short`: sáu đường dẫn trong bảng C mang trạng thái `M`; 15 đường dẫn trong bảng B mang trạng thái `??` (Git có thể gộp thư mục mới). `git diff --stat` gồm SQL khởi tạo, diagram, nhật ký và ba test config; không có thay đổi service/controller hoặc frontend. `git diff --check` đạt.

Xác minh bằng:

```powershell
git status --short
git diff --stat
git ls-files --others --exclude-standard
git diff --check
```

`git diff --stat` mặc định chưa tính các file untracked. Không dùng số dòng của lệnh này để kết luận các file mới chưa tồn tại.

## I. Trạng thái bàn giao

**P0.3 IMPLEMENTATION COMPLETE — READY FOR P0.4 REVIEW**

**Chưa áp dụng migration lên Supabase**; file SQL và 207 test đạt trên môi trường cô lập không chứng minh triển khai database dùng chung. Dừng tại nền móng P0.3; chờ người dùng review trước P0.4.
