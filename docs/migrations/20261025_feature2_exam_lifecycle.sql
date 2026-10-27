-- Chỉ mở rộng CHECK trạng thái; không sửa trạng thái, điểm, câu trả lời hoặc snapshot hiện có.
-- Chạy một lần trên Supabase SQL Editor sau migration snapshot; có thể chạy lại an toàn.
BEGIN;
SET LOCAL lock_timeout = '5s';
ALTER TABLE aives.student_exams
    DROP CONSTRAINT IF EXISTS student_exams_status_check;
ALTER TABLE aives.student_exams
    ADD CONSTRAINT student_exams_status_check
    CHECK (status IN ('ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'ABSENT', 'EXPIRED'));
COMMIT;
