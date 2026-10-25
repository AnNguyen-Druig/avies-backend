-- Chỉ đọc. Chạy sau migration 20261025 trên đúng project/schema đã được phê duyệt.
-- Mọi dòng ở nhóm schema phải PASS. Legacy là danh sách cần rà soát, không phải lệnh backfill.
WITH expected(table_name,column_name,data_type) AS (VALUES
    ('exam_sessions','rubric_snapshot','text'),
    ('exam_sessions','rubric_snapshot_at','timestamp with time zone'),
    ('student_exam_questions','snapshot_content','text'),
    ('student_exam_questions','snapshot_reference_answer','text'),
    ('student_exam_questions','snapshot_bloom_level','character varying'),
    ('student_exam_questions','snapshot_answer_time_limit_seconds','integer'),
    ('student_exam_questions','snapshot_question_version','bigint'),
    ('student_exam_questions','snapshot_captured_at','timestamp with time zone'))
SELECT e.table_name,e.column_name,
       CASE WHEN c.data_type=e.data_type AND c.is_nullable='YES' AND c.column_default IS NULL
            THEN 'PASS' ELSE 'FAIL' END AS schema_check
FROM expected e LEFT JOIN information_schema.columns c
    ON c.table_schema='aives' AND c.table_name=e.table_name AND c.column_name=e.column_name;

WITH expected(table_name,constraint_name) AS (VALUES
    ('exam_sessions','ck_exam_rubric_snapshot'),('student_exam_questions','ck_exam_question_snapshot'))
SELECT e.table_name,e.constraint_name,
       CASE WHEN c.contype='c' AND c.convalidated THEN 'PASS' ELSE 'FAIL' END AS constraint_check
FROM expected e LEFT JOIN pg_constraint c
    ON c.conrelid=to_regclass('aives.'||e.table_name) AND c.conname=e.constraint_name;

WITH expected(table_name,trigger_name) AS (VALUES
    ('exam_sessions','trg_exam_rubric_snapshot'),('student_exam_questions','trg_exam_question_snapshot'))
SELECT e.table_name,e.trigger_name,
       CASE WHEN t.tgenabled='O' AND NOT t.tgisinternal THEN 'PASS' ELSE 'FAIL' END AS trigger_check
FROM expected e LEFT JOIN pg_trigger t
    ON t.tgrelid=to_regclass('aives.'||e.table_name) AND t.tgname=e.trigger_name;

-- Không có snapshot ở phiên chưa chọn câu là bình thường. Bài ĐÃ GÁN CÂU thiếu snapshot cần rà soát.
SELECT e.session_id,a.student_exam_id,a.id AS student_exam_question_id,
       s.rubric_snapshot_at,a.snapshot_captured_at
FROM aives.student_exam_questions a JOIN aives.student_exams e ON e.id=a.student_exam_id
JOIN aives.exam_sessions s ON s.id=e.session_id
WHERE s.rubric_snapshot_at IS NULL OR a.snapshot_captured_at IS NULL
ORDER BY e.session_id,a.student_exam_id,a.question_order;
