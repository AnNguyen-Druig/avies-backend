-- CHỈ ĐỌC: chạy thủ công trên Supabase để đối chiếu sau migration Question CRUD.
-- Không tạo/sửa/xóa schema hoặc dữ liệu. Không chạy file migration lần nữa.
-- Kết quả mong đợi: mọi dòng có passed = true; false cần kiểm tra trước khi kết luận.
WITH expected_columns(name) AS (
    VALUES ('id'), ('subject_id'), ('created_by'), ('content'), ('bloom_level'),
           ('is_ai_generated'), ('is_approved'), ('created_at'), ('reference_answer'),
           ('answer_time_limit_seconds'), ('source_type'), ('status'), ('updated_by'),
           ('updated_at'), ('archived_at'), ('version')
), expected_tables(name) AS (
    VALUES ('question_topics'), ('lecturer_subject_assignments'), ('question_audit_logs')
), expected_checks(name) AS (
    VALUES ('ck_question_bloom_mvp'), ('ck_question_answer_seconds'),
           ('ck_question_source'), ('ck_question_status'), ('ck_question_version')
), expected_indexes(name) AS (
    VALUES ('uq_question_topics_normalized'), ('idx_assignment_subject'),
           ('idx_question_audit_question'), ('idx_question_subject_status'), ('idx_question_creator')
)
SELECT 'column: ' || name AS check_name, EXISTS (
    SELECT 1 FROM information_schema.columns c
    WHERE c.table_schema = 'aives' AND c.table_name = 'questions' AND c.column_name = name
) AS passed FROM expected_columns
UNION ALL
SELECT 'table + RLS: ' || name, EXISTS (
    SELECT 1 FROM pg_tables t WHERE t.schemaname = 'aives' AND t.tablename = name AND t.rowsecurity
) FROM expected_tables
UNION ALL
SELECT 'validated CHECK: ' || name, EXISTS (
    SELECT 1 FROM pg_constraint c
    WHERE c.conrelid = to_regclass('aives.questions') AND c.conname = name
      AND c.contype = 'c' AND c.convalidated
) FROM expected_checks
UNION ALL
SELECT 'valid index: ' || name, EXISTS (
    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_index i ON i.indexrelid = c.oid
    WHERE n.nspname = 'aives' AND c.relname = name AND i.indisvalid AND i.indisready
) FROM expected_indexes
ORDER BY check_name;

-- Đối chiếu kiểu dữ liệu, nullability và default, không chỉ kiểm tra tên cột.
SELECT column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'aives' AND table_name = 'questions'
ORDER BY ordinal_position;

-- Kiểm tra định nghĩa khóa ngoại/unique/check để tránh chỉ dựa vào tên đối tượng.
SELECT c.relname AS table_name, k.conname, pg_get_constraintdef(k.oid) AS definition
FROM pg_constraint k
JOIN pg_class c ON c.oid = k.conrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'aives'
  AND c.relname IN ('questions', 'question_topics', 'lecturer_subject_assignments', 'question_audit_logs')
ORDER BY c.relname, k.conname;

-- Role hiện tại có thể bypass RLS; kết quả đọc bằng postgres không chứng minh client được phép đọc.
SELECT current_database(), current_user, rolbypassrls, rolsuper FROM pg_roles WHERE rolname = current_user;
