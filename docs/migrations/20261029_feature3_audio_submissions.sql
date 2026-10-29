-- Bước STT: chỉ thêm metadata nullable, không thay transcript hoặc tiến trình đã có.
BEGIN;
ALTER TABLE aives.oral_answer_submissions
    ADD COLUMN IF NOT EXISTS audio_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS audio_media_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS audio_bytes INTEGER,
    ADD COLUMN IF NOT EXISTS audio_received_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS audio_storage_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS stt_claim_id UUID,
    ADD COLUMN IF NOT EXISTS stt_claim_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS stt_error_code INTEGER;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='aives.oral_answer_submissions'::regclass AND conname='ck_oral_audio_metadata') THEN
        ALTER TABLE aives.oral_answer_submissions ADD CONSTRAINT ck_oral_audio_metadata CHECK (
            (audio_sha256 IS NULL AND audio_media_type IS NULL AND audio_bytes IS NULL AND audio_received_at IS NULL AND audio_storage_id IS NULL)
            OR (audio_sha256 IS NOT NULL AND audio_sha256 ~ '^[0-9a-f]{64}$'
                AND audio_media_type IS NOT NULL AND audio_media_type IN ('audio/webm','audio/ogg','audio/mp4','audio/wav')
                AND audio_bytes IS NOT NULL AND audio_bytes BETWEEN 128 AND 8388608 AND audio_received_at IS NOT NULL
                AND audio_storage_id IS NOT NULL AND audio_storage_id ~ '^[0-9a-f-]{36}$'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='aives.oral_answer_submissions'::regclass AND conname='ck_oral_stt_claim') THEN
        ALTER TABLE aives.oral_answer_submissions ADD CONSTRAINT ck_oral_stt_claim CHECK (
            (stt_claim_id IS NULL) = (stt_claim_until IS NULL));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_oral_stt_pending ON aives.oral_answer_submissions(stt_claim_until)
    WHERE status='PROCESSING' AND audio_sha256 IS NOT NULL;
COMMIT;
