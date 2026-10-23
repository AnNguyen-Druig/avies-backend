-- Migration: Chuyển quan hệ Rubrics từ Question sang Subject (Mỗi môn học có 1 bộ Rubric dùng chung)
-- Schema: aives

BEGIN;

-- 1. Xóa sạch dữ liệu rubric cũ và các tiêu chí con của nó
TRUNCATE TABLE aives.rubric_criteria, aives.rubrics CASCADE;

-- 2. Xóa cột question_id cũ và thêm cột subject_id liên kết với bảng aives.subjects
ALTER TABLE aives.rubrics
    DROP COLUMN IF EXISTS question_id CASCADE,
    ADD COLUMN subject_id BIGINT NOT NULL 
        REFERENCES aives.subjects(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT uq_rubric_subject UNIQUE (subject_id);

CREATE INDEX IF NOT EXISTS idx_rubric_subject ON aives.rubrics(subject_id);

COMMIT;
