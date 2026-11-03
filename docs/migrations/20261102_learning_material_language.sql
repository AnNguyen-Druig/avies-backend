-- Migration: Thêm thuộc tính language vào bảng aives.learning_materials
-- Phục vụ nhận diện và cấu hình ngôn ngữ (vi / en) cho tài liệu học tập & sinh câu hỏi theo ngôn ngữ tương ứng.

BEGIN;

-- 1. Thêm cột language với giá trị mặc định là 'vi'
ALTER TABLE aives.learning_materials
    ADD COLUMN IF NOT EXISTS language VARCHAR(10) NOT NULL DEFAULT 'vi';

-- 2. Đặt ràng buộc CHECK chỉ cho phép 'vi' hoặc 'en'
DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conrelid = 'aives.learning_materials'::regclass 
          AND conname = 'ck_learning_materials_language'
    ) THEN
        ALTER TABLE aives.learning_materials 
            ADD CONSTRAINT ck_learning_materials_language 
            CHECK (language IN ('vi', 'en'));
    END IF;
END $$;

-- 3. Tạo index phục vụ lọc hoặc tìm kiếm theo ngôn ngữ
CREATE INDEX IF NOT EXISTS idx_learning_materials_language
    ON aives.learning_materials(language);

COMMIT;

