-- Migration: Cập nhật CHECK constraint bloom_level đủ 6 mức nhận thức Bloom
-- Phạm vi: Bảng aives.questions
-- Các mức Bloom: REMEMBER, UNDERSTAND, APPLY, ANALYZE, EVALUATE, CREATE

BEGIN;

-- 1. Tìm và xóa constraint cũ kiểm tra bloom_level (dù tên là ck_question_bloom_mvp hay questions_bloom_level_check)
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = 'aives'
          AND rel.relname = 'questions'
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%bloom_level%'
    ) LOOP
        EXECUTE format('ALTER TABLE aives.questions DROP CONSTRAINT %I', r.conname);
    END LOOP;
END $$;

-- 2. Thêm constraint mới hỗ trợ đầy đủ 6 level Bloom
ALTER TABLE aives.questions
    ADD CONSTRAINT ck_question_bloom_mvp
    CHECK (
        bloom_level IS NULL OR bloom_level IN (
            'REMEMBER',
            'UNDERSTAND',
            'APPLY',
            'ANALYZE',
            'EVALUATE',
            'CREATE'
        )
    );

COMMIT;
