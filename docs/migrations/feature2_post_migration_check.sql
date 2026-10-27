-- CHỈ ĐỌC — không thay schema hoặc dữ liệu; an toàn chạy trên Supabase SQL Editor.
-- Mong đợi tất cả passed=true/result=PASS. FAIL cần review định nghĩa thực tế, không tắt constraint.
-- CHECK được so sánh định nghĩa PostgreSQL, chỉ bỏ khoảng trắng; khác cách biểu diễn cũng FAIL bảo thủ.
-- Kiểm tra cột gốc theo SQL repository: metadata/số lượng bản ghi live chưa được cung cấp.
-- CHECK trạng thái lượt thi nhận đúng baseline 3 trạng thái hoặc lifecycle 5 trạng thái đã phê duyệt.
WITH expected_tables(table_name) AS (
    VALUES ('exam_sessions'), ('student_exams'), ('student_exam_questions')
), expected_columns(table_name, column_name, type_name, not_null) AS (
    VALUES
    ('exam_sessions', 'id', 'bigint', true),
    ('exam_sessions', 'lecturer_id', 'bigint', true),
    ('exam_sessions', 'subject_id', 'bigint', true),
    ('exam_sessions', 'title', 'character varying(255)', true),
    ('exam_sessions', 'status', 'character varying(20)', true),
    ('exam_sessions', 'max_main_questions', 'integer', true),
    ('exam_sessions', 'max_follow_ups', 'integer', true),
    ('exam_sessions', 'created_at', 'timestamp with time zone', true),
    ('exam_sessions', 'scheduled_start_at', 'timestamp with time zone', false),
    ('exam_sessions', 'scheduled_end_at', 'timestamp with time zone', false),
    ('exam_sessions', 'slot_duration_minutes', 'integer', false),
    ('student_exams', 'id', 'bigint', true),
    ('student_exams', 'session_id', 'bigint', true),
    ('student_exams', 'student_id', 'bigint', true),
    ('student_exams', 'status', 'character varying(20)', true),
    ('student_exams', 'ai_suggested_score', 'numeric(5,2)', false),
    ('student_exams', 'final_score', 'numeric(5,2)', false),
    ('student_exams', 'ai_feedback', 'text', false),
    ('student_exams', 'audio_record_url', 'character varying(500)', false),
    ('student_exams', 'started_at', 'timestamp with time zone', false),
    ('student_exams', 'completed_at', 'timestamp with time zone', false),
    ('student_exams', 'slot_order', 'integer', false),
    ('student_exams', 'scheduled_start_at', 'timestamp with time zone', false),
    ('student_exams', 'scheduled_end_at', 'timestamp with time zone', false),
    ('student_exam_questions', 'id', 'bigint', true),
    ('student_exam_questions', 'student_exam_id', 'bigint', true),
    ('student_exam_questions', 'question_id', 'bigint', true),
    ('student_exam_questions', 'question_order', 'integer', true)
), expected_keys(table_name, constraint_name, kind, columns, ref_table, ref_columns, on_update, on_delete) AS (
    VALUES
    ('exam_sessions', 'exam_sessions_pkey', 'p', ARRAY['id']::text[], NULL, NULL, NULL, NULL),
    ('exam_sessions', 'exam_sessions_lecturer_id_fkey', 'f', ARRAY['lecturer_id']::text[], 'users', ARRAY['id']::text[], 'c', 'r'),
    ('exam_sessions', 'exam_sessions_subject_id_fkey', 'f', ARRAY['subject_id']::text[], 'subjects', ARRAY['id']::text[], 'c', 'r'),
    ('student_exams', 'student_exams_pkey', 'p', ARRAY['id']::text[], NULL, NULL, NULL, NULL),
    ('student_exams', 'student_exams_session_id_fkey', 'f', ARRAY['session_id']::text[], 'exam_sessions', ARRAY['id']::text[], 'c', 'c'),
    ('student_exams', 'student_exams_student_id_fkey', 'f', ARRAY['student_id']::text[], 'users', ARRAY['id']::text[], 'c', 'r'),
    ('student_exams', 'uq_exam_session_student', 'u', ARRAY['session_id', 'student_id']::text[], NULL, NULL, NULL, NULL),
    ('student_exams', 'uq_student_exam_slot_order', 'u', ARRAY['session_id', 'slot_order']::text[], NULL, NULL, NULL, NULL),
    ('student_exam_questions', 'student_exam_questions_pkey', 'p', ARRAY['id']::text[], NULL, NULL, NULL, NULL),
    ('student_exam_questions', 'student_exam_questions_student_exam_id_fkey', 'f', ARRAY['student_exam_id']::text[], 'student_exams', ARRAY['id']::text[], 'c', 'c'),
    ('student_exam_questions', 'student_exam_questions_question_id_fkey', 'f', ARRAY['question_id']::text[], 'questions', ARRAY['id']::text[], 'c', 'r'),
    ('student_exam_questions', 'uq_student_exam_question', 'u', ARRAY['student_exam_id', 'question_id']::text[], NULL, NULL, NULL, NULL),
    ('student_exam_questions', 'uq_student_exam_question_order', 'u', ARRAY['student_exam_id', 'question_order']::text[], NULL, NULL, NULL, NULL)
), expected_checks(table_name, constraint_name, definition) AS (
    VALUES
    ('exam_sessions', 'exam_sessions_max_main_questions_check', 'CHECK ((max_main_questions > 0))'),
    ('exam_sessions', 'exam_sessions_max_follow_ups_check', 'CHECK ((max_follow_ups >= 0))'),
    ('exam_sessions', 'exam_sessions_status_check', 'CHECK (((status)::text = ANY ((ARRAY[''UPCOMING''::character varying, ''ONGOING''::character varying, ''COMPLETED''::character varying])::text[])))'),
    ('student_exams', 'student_exams_status_check', 'CHECK (((status)::text = ANY ((ARRAY[''ASSIGNED''::character varying, ''IN_PROGRESS''::character varying, ''COMPLETED''::character varying])::text[])))'),
    ('student_exams', 'chk_ai_suggested_score', 'CHECK (((ai_suggested_score IS NULL) OR ((ai_suggested_score >= (0)::numeric) AND (ai_suggested_score <= (10)::numeric))))'),
    ('student_exams', 'chk_final_score', 'CHECK (((final_score IS NULL) OR ((final_score >= (0)::numeric) AND (final_score <= (10)::numeric))))'),
    ('student_exams', 'chk_student_exam_time', 'CHECK (((completed_at IS NULL) OR (started_at IS NULL) OR (completed_at >= started_at)))'),
    ('student_exam_questions', 'student_exam_questions_question_order_check', 'CHECK ((question_order > 0))'),
    ('exam_sessions', 'ck_exam_session_schedule', 'CHECK ((((scheduled_start_at IS NULL) AND (scheduled_end_at IS NULL)) OR ((scheduled_start_at IS NOT NULL) AND (scheduled_end_at IS NOT NULL) AND (scheduled_end_at > scheduled_start_at))))'),
    ('exam_sessions', 'ck_exam_session_slot_duration', 'CHECK (((slot_duration_minutes IS NULL) OR (slot_duration_minutes > 0)))'),
    ('student_exams', 'ck_student_exam_schedule', 'CHECK ((((scheduled_start_at IS NULL) AND (scheduled_end_at IS NULL)) OR ((scheduled_start_at IS NOT NULL) AND (scheduled_end_at IS NOT NULL) AND (scheduled_end_at > scheduled_start_at))))'),
    ('student_exams', 'ck_student_exam_slot_order', 'CHECK (((slot_order IS NULL) OR (slot_order > 0)))')
), expected_indexes(table_name, index_name, columns) AS (
    VALUES
    ('exam_sessions', 'idx_exam_sessions_lecturer_id', ARRAY['lecturer_id']::text[]),
    ('exam_sessions', 'idx_exam_sessions_subject_id', ARRAY['subject_id']::text[]),
    ('student_exams', 'idx_student_exams_session_id', ARRAY['session_id']::text[]),
    ('student_exams', 'idx_student_exams_student_id', ARRAY['student_id']::text[]),
    ('student_exam_questions', 'idx_student_exam_questions_exam_id', ARRAY['student_exam_id']::text[]),
    ('student_exam_questions', 'idx_student_exam_questions_question_id', ARRAY['question_id']::text[])
), invariants AS (
    SELECT 'table: ' || e.table_name AS check_name, EXISTS (
        SELECT 1 FROM pg_class t JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'aives' AND t.relname = e.table_name AND t.relkind = 'r'
    ) AS passed FROM expected_tables e
    UNION ALL
    SELECT 'column type/nullability: ' || e.table_name || '.' || e.column_name, EXISTS (
        SELECT 1 FROM pg_attribute a
        WHERE a.attrelid = to_regclass('aives.' || e.table_name) AND a.attname = e.column_name
          AND a.attnum > 0 AND NOT a.attisdropped
          AND format_type(a.atttypid, a.atttypmod) = e.type_name AND a.attnotnull = e.not_null
    ) FROM expected_columns e
    UNION ALL
    SELECT 'identity: ' || e.table_name || '.id', EXISTS (
        SELECT 1 FROM pg_attribute a WHERE a.attrelid = to_regclass('aives.' || e.table_name)
          AND a.attname = 'id' AND a.attidentity IN ('a', 'd')
    ) FROM expected_tables e
    UNION ALL
    SELECT 'PK/FK/UNIQUE: ' || e.constraint_name, EXISTS (
        SELECT 1 FROM pg_constraint c
        WHERE c.conrelid = to_regclass('aives.' || e.table_name) AND c.conname = e.constraint_name
          AND c.contype::text = e.kind AND c.convalidated AND NOT c.condeferrable
          AND NOT c.condeferred
          AND ARRAY(SELECT a.attname::text FROM unnest(c.conkey) WITH ORDINALITY k(num, pos)
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.num
                    ORDER BY k.pos) = e.columns
          AND CASE WHEN e.kind = 'f' THEN
              c.confrelid = to_regclass('aives.' || e.ref_table)
              AND ARRAY(SELECT a.attname::text FROM unnest(c.confkey) WITH ORDINALITY k(num, pos)
                        JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.num
                        ORDER BY k.pos) = e.ref_columns
              AND c.confupdtype::text = e.on_update AND c.confdeltype::text = e.on_delete
              AND c.confmatchtype = 's'
            ELSE EXISTS (
              SELECT 1 FROM pg_index i WHERE i.indexrelid = c.conindid
                AND i.indisunique AND i.indisvalid AND i.indisready
                AND NOT i.indnullsnotdistinct AND i.indpred IS NULL AND i.indexprs IS NULL
            ) END
    ) FROM expected_keys e
    UNION ALL
    SELECT 'validated CHECK definition: ' || e.constraint_name, EXISTS (
        SELECT 1 FROM pg_constraint c
        WHERE c.conrelid = to_regclass('aives.' || e.table_name) AND c.conname = e.constraint_name
          AND c.contype = 'c' AND c.convalidated AND NOT c.connoinherit
          -- Chỉ bỏ khoảng trắng; giữ dấu ngoặc, toán tử và casts để không che khác biệt logic.
          AND (regexp_replace(pg_get_constraintdef(c.oid), '[[:space:]]', '', 'g')
              = regexp_replace(e.definition, '[[:space:]]', '', 'g')
              OR (e.table_name = 'student_exams' AND e.constraint_name = 'student_exams_status_check'
                  AND regexp_replace(pg_get_constraintdef(c.oid), '[[:space:]]', '', 'g') = regexp_replace(
                      'CHECK (((status)::text = ANY ((ARRAY[''ASSIGNED''::character varying, ''IN_PROGRESS''::character varying, ''COMPLETED''::character varying, ''ABSENT''::character varying, ''EXPIRED''::character varying])::text[])))',
                      '[[:space:]]', '', 'g')))
    ) FROM expected_checks e
    UNION ALL
    SELECT 'valid index definition: ' || e.index_name, EXISTS (
        SELECT 1 FROM pg_index i JOIN pg_class idx ON idx.oid = i.indexrelid
        JOIN pg_namespace n ON n.oid = idx.relnamespace JOIN pg_am am ON am.oid = idx.relam
        WHERE n.nspname = 'aives' AND idx.relname = e.index_name
          AND i.indrelid = to_regclass('aives.' || e.table_name)
          AND i.indisvalid AND i.indisready AND i.indpred IS NULL AND i.indexprs IS NULL
          AND am.amname = 'btree' AND i.indnatts = cardinality(e.columns)
          AND ARRAY(SELECT a.attname::text FROM unnest(i.indkey::smallint[]) WITH ORDINALITY k(num, pos)
                    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.num
                    ORDER BY k.pos) = e.columns
    ) FROM expected_indexes e
)
SELECT check_name, COALESCE(passed, false) AS passed,
       CASE WHEN COALESCE(passed, false) THEN 'PASS' ELSE 'FAIL' END AS result
FROM invariants
UNION ALL
SELECT 'schedule columns have no defaults', NOT EXISTS (
    SELECT 1 FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE (a.attrelid = to_regclass('aives.exam_sessions') AND a.attname IN ('scheduled_start_at', 'scheduled_end_at', 'slot_duration_minutes'))
       OR (a.attrelid = to_regclass('aives.student_exams') AND a.attname IN ('slot_order', 'scheduled_start_at', 'scheduled_end_at'))
), CASE WHEN NOT EXISTS (
    SELECT 1 FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE (a.attrelid = to_regclass('aives.exam_sessions') AND a.attname IN ('scheduled_start_at', 'scheduled_end_at', 'slot_duration_minutes'))
       OR (a.attrelid = to_regclass('aives.student_exams') AND a.attname IN ('slot_order', 'scheduled_start_at', 'scheduled_end_at'))
) THEN 'PASS' ELSE 'FAIL' END
ORDER BY check_name;
