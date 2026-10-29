-- Claim riêng cho quyết định AI; không thay câu hỏi, rubric hoặc transcript đã lưu.
BEGIN;
ALTER TABLE aives.oral_answer_submissions
    ADD COLUMN IF NOT EXISTS decision_claim_id UUID,
    ADD COLUMN IF NOT EXISTS decision_claim_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS decision_error_code INTEGER;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='aives.oral_answer_submissions'::regclass AND conname='ck_oral_decision_claim') THEN
        ALTER TABLE aives.oral_answer_submissions ADD CONSTRAINT ck_oral_decision_claim CHECK (
            (decision_claim_id IS NULL) = (decision_claim_until IS NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='aives.oral_answer_submissions'::regclass AND conname='ck_oral_decision_error') THEN
        ALTER TABLE aives.oral_answer_submissions ADD CONSTRAINT ck_oral_decision_error CHECK (
            decision_error_code IS NULL OR decision_error_code IN (1815,1816,1817));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_oral_decision_pending ON aives.oral_answer_submissions(decision_claim_until)
    WHERE status='TRANSCRIBED';
COMMIT;
