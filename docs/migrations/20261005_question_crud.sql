-- ĐỀ XUẤT MIGRATION: chưa được áp dụng vào database.
-- Chỉ chạy thủ công sau khi chủ dự án duyệt việc áp dụng và sao lưu dữ liệu.
-- Không đặt file này trong đường dẫn tự chạy của Flyway/Liquibase.
-- Có thể chạy lại: không ghi đè dữ liệu đã được phân loại thủ công.
BEGIN;

-- Không tự đổi EVALUATE/CREATE về một mức Bloom khác vì sai nghĩa nghiệp vụ.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM aives.questions
               WHERE bloom_level IS NOT NULL
                 AND bloom_level NOT IN ('REMEMBER', 'UNDERSTAND', 'APPLY', 'ANALYZE')) THEN
        RAISE EXCEPTION 'Cần duyệt cách xử lý Bloom ngoài bốn mức MVP trước khi áp dụng migration Question.';
    END IF;
END $$;

ALTER TABLE aives.questions
    ADD COLUMN IF NOT EXISTS reference_answer TEXT,
    ADD COLUMN IF NOT EXISTS answer_time_limit_seconds INTEGER,
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS updated_by BIGINT REFERENCES aives.users(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Chỉ chuyển đổi những thông tin có thể xác định chắc chắn từ cờ cũ.
UPDATE aives.questions SET source_type = 'AI'
WHERE source_type IS NULL AND is_ai_generated IS TRUE;
UPDATE aives.questions SET status = 'APPROVED'
WHERE status IS NULL AND is_approved IS TRUE;
UPDATE aives.questions SET updated_at = created_at WHERE updated_at IS NULL;

-- Giữ NULL cho nguồn MANUAL/IMPORT và trạng thái DRAFT/REJECTED/PENDING_REVIEW
-- chưa thể suy ra. API sẽ khóa sửa/xóa khi status chưa được xác minh.
-- Không xóa hai cờ cũ; cần đợt duyệt dữ liệu riêng trước khi loại bỏ chúng.
ALTER TABLE aives.questions
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN source_type SET DEFAULT 'MANUAL',
    ALTER COLUMN status SET DEFAULT 'DRAFT',
    ALTER COLUMN is_ai_generated SET DEFAULT FALSE;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_question_bloom_mvp' AND conrelid = 'aives.questions'::regclass) THEN
        ALTER TABLE aives.questions ADD CONSTRAINT ck_question_bloom_mvp
            CHECK (bloom_level IS NULL OR bloom_level IN ('REMEMBER', 'UNDERSTAND', 'APPLY', 'ANALYZE'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_question_answer_seconds' AND conrelid = 'aives.questions'::regclass) THEN
        ALTER TABLE aives.questions ADD CONSTRAINT ck_question_answer_seconds
            CHECK (answer_time_limit_seconds IS NULL OR answer_time_limit_seconds > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_question_source' AND conrelid = 'aives.questions'::regclass) THEN
        ALTER TABLE aives.questions ADD CONSTRAINT ck_question_source
            CHECK (source_type IS NULL OR source_type IN ('MANUAL', 'IMPORT', 'AI'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_question_status' AND conrelid = 'aives.questions'::regclass) THEN
        ALTER TABLE aives.questions ADD CONSTRAINT ck_question_status
            CHECK (status IS NULL OR status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_question_version' AND conrelid = 'aives.questions'::regclass) THEN
        ALTER TABLE aives.questions ADD CONSTRAINT ck_question_version CHECK (version >= 0);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS aives.question_topics (
    question_id BIGINT NOT NULL REFERENCES aives.questions(id) ON DELETE CASCADE,
    topic VARCHAR(100) NOT NULL CHECK (btrim(topic) <> ''),
    PRIMARY KEY (question_id, topic)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_question_topics_normalized
    ON aives.question_topics(question_id, lower(topic));

CREATE TABLE IF NOT EXISTS aives.lecturer_subject_assignments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lecturer_id BIGINT NOT NULL REFERENCES aives.users(id) ON DELETE RESTRICT,
    subject_id BIGINT NOT NULL REFERENCES aives.subjects(id) ON DELETE RESTRICT,
    CONSTRAINT uq_lecturer_subject_assignment UNIQUE (lecturer_id, subject_id)
);
CREATE INDEX IF NOT EXISTS idx_assignment_subject ON aives.lecturer_subject_assignments(subject_id);

CREATE TABLE IF NOT EXISTS aives.question_audit_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Không dùng FK đến questions: cần giữ nhật ký sau khi xóa bản nháp.
    question_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL REFERENCES aives.users(id) ON DELETE RESTRICT,
    action VARCHAR(30) NOT NULL CHECK (action IN ('CREATED', 'UPDATED', 'DELETED')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_question_audit_question ON aives.question_audit_logs(question_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_question_subject_status ON aives.questions(subject_id, status, created_at, id);
CREATE INDEX IF NOT EXISTS idx_question_creator ON aives.questions(created_by, created_at, id);

COMMIT;

-- Sau khi áp dụng có phê duyệt: xem các bản ghi cần phân loại, không tự UPDATE.
-- SELECT id, is_ai_generated, is_approved, source_type, status
-- FROM aives.questions WHERE source_type IS NULL OR status IS NULL;
-- Dữ liệu phân công giảng viên phải do admin/chủ dự án xác nhận riêng.
