-- Migration: Thêm thuộc tính language vào bảng aives.questions
-- Cho phép phân loại, quản lý và tìm kiếm câu hỏi theo ngôn ngữ (vi / en).

BEGIN;

-- 1. Thêm cột language với giá trị mặc định là 'vi'
ALTER TABLE aives.questions
    ADD COLUMN IF NOT EXISTS language VARCHAR(10) NOT NULL DEFAULT 'vi';

-- 2. Đặt ràng buộc CHECK chỉ cho phép 'vi' hoặc 'en'
DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conrelid = 'aives.questions'::regclass 
          AND conname = 'ck_questions_language'
    ) THEN
        ALTER TABLE aives.questions 
            ADD CONSTRAINT ck_questions_language 
            CHECK (language IN ('vi', 'en'));
    END IF;
END $$;

-- 3. Tạo index phục vụ lọc danh sách ngân hàng câu hỏi theo ngôn ngữ
CREATE INDEX IF NOT EXISTS idx_questions_language
    ON aives.questions(language);

COMMIT;

