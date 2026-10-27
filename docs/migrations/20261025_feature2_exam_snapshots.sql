-- Feature 2: snapshot lịch sử. Chỉ chạy khi người phụ trách đã duyệt và sao lưu.
-- Không xóa/UPDATE dữ liệu cũ, không backfill từ ngân hàng hiện hành.
-- Yêu cầu: foundation 20261016 và rubric cấp môn 20261022 đã được áp dụng.
BEGIN;
SET LOCAL lock_timeout = '5s';

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='aives' AND table_name='rubrics' AND column_name='subject_id') THEN
        RAISE EXCEPTION 'Can migration rubric cap mon truoc khi them snapshot';
END IF;
END $$;

ALTER TABLE aives.exam_sessions
    ADD COLUMN IF NOT EXISTS rubric_snapshot TEXT,
    ADD COLUMN IF NOT EXISTS rubric_snapshot_at TIMESTAMPTZ;

ALTER TABLE aives.student_exam_questions
    ADD COLUMN IF NOT EXISTS snapshot_content TEXT,
    ADD COLUMN IF NOT EXISTS snapshot_reference_answer TEXT,
    ADD COLUMN IF NOT EXISTS snapshot_bloom_level VARCHAR(20),
    ADD COLUMN IF NOT EXISTS snapshot_answer_time_limit_seconds INTEGER,
    ADD COLUMN IF NOT EXISTS snapshot_question_version BIGINT,
    ADD COLUMN IF NOT EXISTS snapshot_captured_at TIMESTAMPTZ;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='aives.exam_sessions'::regclass
                   AND conname='ck_exam_rubric_snapshot') THEN
ALTER TABLE aives.exam_sessions ADD CONSTRAINT ck_exam_rubric_snapshot CHECK (
    (rubric_snapshot IS NULL AND rubric_snapshot_at IS NULL) OR
    (rubric_snapshot IS NOT NULL AND rubric_snapshot_at IS NOT NULL AND COALESCE(
            jsonb_typeof(rubric_snapshot::jsonb)='object'
                AND rubric_snapshot::jsonb->>'schemaVersion'='1'
                AND jsonb_typeof(rubric_snapshot::jsonb->'criteria')='array', FALSE)));
END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='aives.student_exam_questions'::regclass
                   AND conname='ck_exam_question_snapshot') THEN
ALTER TABLE aives.student_exam_questions ADD CONSTRAINT ck_exam_question_snapshot CHECK (
    (snapshot_captured_at IS NULL AND snapshot_content IS NULL AND snapshot_reference_answer IS NULL
        AND snapshot_bloom_level IS NULL AND snapshot_answer_time_limit_seconds IS NULL
        AND snapshot_question_version IS NULL) OR
    (snapshot_captured_at IS NOT NULL AND snapshot_content IS NOT NULL AND length(btrim(snapshot_content))>0
        AND snapshot_question_version IS NOT NULL AND snapshot_question_version>=0
        AND snapshot_bloom_level IS NOT NULL
        AND snapshot_bloom_level IN ('REMEMBER','UNDERSTAND','APPLY','ANALYZE','EVALUATE','CREATE')
        AND (snapshot_answer_time_limit_seconds IS NULL OR snapshot_answer_time_limit_seconds>0)));
END IF;
END $$;

-- Bảo vệ cả raw UPDATE: snapshot đã chốt chỉ đọc, không đổi môn hoặc cấu hình đề của phiên.
CREATE OR REPLACE FUNCTION aives.guard_exam_rubric_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.rubric_snapshot_at IS NOT NULL AND
       ROW(NEW.rubric_snapshot,NEW.rubric_snapshot_at,NEW.subject_id,NEW.max_main_questions,NEW.max_follow_ups)
       IS DISTINCT FROM ROW(OLD.rubric_snapshot,OLD.rubric_snapshot_at,OLD.subject_id,OLD.max_main_questions,OLD.max_follow_ups) THEN
        RAISE EXCEPTION 'Exam rubric snapshot is immutable' USING ERRCODE='23514';
END IF;
RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS trg_exam_rubric_snapshot ON aives.exam_sessions;
CREATE TRIGGER trg_exam_rubric_snapshot BEFORE UPDATE ON aives.exam_sessions
    FOR EACH ROW EXECUTE FUNCTION aives.guard_exam_rubric_snapshot();

-- Không đổi câu nguồn/thứ tự hoặc bất kỳ trường snapshot nào sau khi chốt.
CREATE OR REPLACE FUNCTION aives.guard_exam_question_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.snapshot_captured_at IS NOT NULL AND
       ROW(NEW.student_exam_id,NEW.question_id,NEW.question_order,NEW.snapshot_content,NEW.snapshot_reference_answer,
           NEW.snapshot_bloom_level,NEW.snapshot_answer_time_limit_seconds,NEW.snapshot_question_version,NEW.snapshot_captured_at)
       IS DISTINCT FROM ROW(OLD.student_exam_id,OLD.question_id,OLD.question_order,OLD.snapshot_content,OLD.snapshot_reference_answer,
           OLD.snapshot_bloom_level,OLD.snapshot_answer_time_limit_seconds,OLD.snapshot_question_version,OLD.snapshot_captured_at) THEN
        RAISE EXCEPTION 'Exam question snapshot is immutable' USING ERRCODE='23514';
END IF;
RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS trg_exam_question_snapshot ON aives.student_exam_questions;
CREATE TRIGGER trg_exam_question_snapshot BEFORE UPDATE ON aives.student_exam_questions
    FOR EACH ROW EXECUTE FUNCTION aives.guard_exam_question_snapshot();

COMMENT ON COLUMN aives.exam_sessions.rubric_snapshot IS 'Rubric JSON schemaVersion=1: nguồn, tiêu đề, mô tả và tiêu chí/điểm; không phải FK';
COMMENT ON COLUMN aives.student_exam_questions.snapshot_reference_answer IS 'Đáp án lúc chọn câu, có thể null; không đọc lại questions.reference_answer khi chấm lịch sử';
COMMIT;

-- Sau triển khai: chỉ kiểm tra, không tự sửa các bài legacy.
-- SELECT a.id FROM aives.student_exam_questions a JOIN aives.student_exams e ON e.id=a.student_exam_id
-- JOIN aives.exam_sessions s ON s.id=e.session_id WHERE a.snapshot_captured_at IS NULL OR s.rubric_snapshot_at IS NULL;
