-- Feature 3, bước 1–2: tiến trình hội thoại, quyền tab và bằng chứng từng lượt.
-- Chạy sau migration Feature 2 snapshot/lifecycle. Không backfill lịch sử hoặc chấm điểm.
BEGIN;
SET LOCAL lock_timeout = '5s';

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='aives.student_exam_questions'::regclass
                   AND conname='uq_oral_source_question_attempt') THEN
        ALTER TABLE aives.student_exam_questions ADD CONSTRAINT uq_oral_source_question_attempt UNIQUE(id,student_exam_id);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS aives.oral_exam_progress (
    student_exam_id BIGINT PRIMARY KEY REFERENCES aives.student_exams(id) ON DELETE RESTRICT,
    phase VARCHAR(25) NOT NULL CHECK (phase IN ('PREPARING','SPEAKING','ANSWERING','TRANSCRIBING','DECIDING','REVIEW','PAUSED','TECHNICAL_ERROR','COMPLETED','EXPIRED')),
    recovery_phase VARCHAR(25),
    current_question_order INT CHECK (current_question_order > 0),
    current_turn_id UUID,
    follow_ups_used INT NOT NULL DEFAULT 0 CHECK (follow_ups_used >= 0),
    max_follow_ups INT NOT NULL CHECK (max_follow_ups >= 0),
    main_count INT NOT NULL CHECK (main_count > 0),
    answer_opened_at TIMESTAMPTZ,
    client_session_id UUID NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (follow_ups_used <= max_follow_ups),
    CHECK ((phase='ANSWERING') = (answer_opened_at IS NOT NULL)),
    CHECK (current_question_order IS NULL OR current_question_order <= main_count)
);

CREATE TABLE IF NOT EXISTS aives.oral_question_progress (
    student_exam_question_id BIGINT PRIMARY KEY,
    student_exam_id BIGINT NOT NULL REFERENCES aives.oral_exam_progress(student_exam_id) ON DELETE RESTRICT,
    question_order INT NOT NULL CHECK (question_order > 0),
    status VARCHAR(25) NOT NULL CHECK (status IN ('PENDING','ACTIVE','DEFERRED','ANSWERED','NO_ANSWER','TECHNICAL_ERROR')),
    answer_budget_ms BIGINT CHECK (answer_budget_ms > 0),
    answer_used_ms BIGINT NOT NULL DEFAULT 0 CHECK (answer_used_ms >= 0),
    outcome_reason VARCHAR(40),
    UNIQUE (student_exam_id,question_order),
    UNIQUE (student_exam_question_id,student_exam_id),
    FOREIGN KEY (student_exam_question_id,student_exam_id)
        REFERENCES aives.student_exam_questions(id,student_exam_id) ON DELETE RESTRICT,
    CHECK (answer_budget_ms IS NULL OR answer_used_ms <= answer_budget_ms)
);

CREATE TABLE IF NOT EXISTS aives.oral_exam_turns (
    id UUID PRIMARY KEY,
    student_exam_id BIGINT NOT NULL,
    student_exam_question_id BIGINT NOT NULL,
    follow_up_index INT NOT NULL CHECK (follow_up_index >= 0),
    prompt_text TEXT NOT NULL CHECK (length(btrim(prompt_text)) > 0),
    language VARCHAR(2) CHECK (language IN ('vi','en')),
    prompt_ready BOOLEAN NOT NULL DEFAULT false,
    answer_status VARCHAR(25) NOT NULL CHECK (answer_status IN ('PENDING','PROCESSING','ANSWERED','NO_ANSWER','TECHNICAL_ERROR')),
    answer_text TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    answered_at TIMESTAMPTZ,
    UNIQUE (student_exam_question_id,follow_up_index),
    UNIQUE (id,student_exam_id),
    UNIQUE (id,student_exam_question_id),
    FOREIGN KEY (student_exam_question_id,student_exam_id)
        REFERENCES aives.oral_question_progress(student_exam_question_id,student_exam_id) ON DELETE RESTRICT,
    CHECK (NOT prompt_ready OR language IS NOT NULL),
    CHECK ((answer_status='ANSWERED') = (answer_text IS NOT NULL AND length(btrim(answer_text)) > 0 AND answered_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS aives.oral_answer_submissions (
    id UUID PRIMARY KEY,
    student_exam_id BIGINT NOT NULL,
    turn_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PROCESSING','TRANSCRIBED','SUCCEEDED','EMPTY','FAILED','CANCELLED')),
    transcript_text TEXT,
    decision_kind VARCHAR(15) CHECK (decision_kind IN ('ADVANCE','FOLLOW_UP')),
    decision_text TEXT,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id,turn_id),
    FOREIGN KEY (turn_id,student_exam_id) REFERENCES aives.oral_exam_turns(id,student_exam_id) ON DELETE RESTRICT,
    CHECK (status NOT IN ('TRANSCRIBED','SUCCEEDED') OR (transcript_text IS NOT NULL AND length(btrim(transcript_text)) > 0))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_oral_pending_submission ON aives.oral_answer_submissions(turn_id)
    WHERE status IN ('PROCESSING','TRANSCRIBED','FAILED');

CREATE TABLE IF NOT EXISTS aives.oral_exam_commands (
    student_exam_id BIGINT NOT NULL REFERENCES aives.oral_exam_progress(student_exam_id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(student_exam_id,request_id)
);

-- Các cột nullable giữ nguyên bằng chứng Feature 2/legacy; hội thoại mới phải có mã lượt.
ALTER TABLE aives.transcripts ADD COLUMN IF NOT EXISTS oral_turn_id UUID;
ALTER TABLE aives.transcripts ADD COLUMN IF NOT EXISTS oral_submission_id UUID;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='aives.transcripts'::regclass AND conname='fk_transcript_oral_turn') THEN
        ALTER TABLE aives.transcripts ADD CONSTRAINT fk_transcript_oral_turn FOREIGN KEY(oral_turn_id) REFERENCES aives.oral_exam_turns(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='aives.transcripts'::regclass AND conname='fk_transcript_oral_submission') THEN
        ALTER TABLE aives.transcripts ADD CONSTRAINT fk_transcript_oral_submission FOREIGN KEY(oral_submission_id) REFERENCES aives.oral_answer_submissions(id) ON DELETE RESTRICT;
    END IF;
END $$;
-- Ràng buộc vòng chỉ kiểm tra khi commit: parent được tạo trước turn trong cùng transaction.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='aives.oral_exam_progress'::regclass AND conname='fk_oral_current_turn') THEN
        ALTER TABLE aives.oral_exam_progress ADD CONSTRAINT fk_oral_current_turn
            FOREIGN KEY(current_turn_id,student_exam_id) REFERENCES aives.oral_exam_turns(id,student_exam_id) DEFERRABLE INITIALLY DEFERRED;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='aives.transcripts'::regclass AND conname='fk_transcript_oral_question') THEN
        ALTER TABLE aives.transcripts ADD CONSTRAINT fk_transcript_oral_question
            FOREIGN KEY(oral_turn_id,student_exam_question_id) REFERENCES aives.oral_exam_turns(id,student_exam_question_id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='aives.transcripts'::regclass AND conname='fk_transcript_oral_submission_turn') THEN
        ALTER TABLE aives.transcripts ADD CONSTRAINT fk_transcript_oral_submission_turn
            FOREIGN KEY(oral_submission_id,oral_turn_id) REFERENCES aives.oral_answer_submissions(id,turn_id) ON DELETE RESTRICT;
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uq_oral_active_question ON aives.oral_question_progress(student_exam_id) WHERE status='ACTIVE';
CREATE UNIQUE INDEX IF NOT EXISTS uq_transcript_oral_turn_type ON aives.transcripts(oral_turn_id,turn_type) WHERE oral_turn_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_transcript_oral_submission ON aives.transcripts(oral_submission_id) WHERE oral_submission_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_oral_turn_attempt ON aives.oral_exam_turns(student_exam_id,student_exam_question_id,follow_up_index);
CREATE INDEX IF NOT EXISTS idx_oral_submission_attempt ON aives.oral_answer_submissions(student_exam_id);

-- Không mở truy cập trực tiếp cho anon/authenticated qua Supabase Data API.
ALTER TABLE aives.oral_exam_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE aives.oral_question_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE aives.oral_exam_turns ENABLE ROW LEVEL SECURITY;
ALTER TABLE aives.oral_answer_submissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE aives.oral_exam_commands ENABLE ROW LEVEL SECURITY;
COMMIT;
