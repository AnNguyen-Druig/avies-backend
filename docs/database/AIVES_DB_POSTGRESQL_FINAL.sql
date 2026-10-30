-- ============================================================
-- AIVES Database Initialization - PostgreSQL / Supabase
-- Version: 2026-10-28 (Feature 3 tiến trình hội thoại; file khởi tạo, không dùng để nâng DB đang có)
-- Database đã tồn tại: dùng migration mới tương ứng sau review và backup.
-- Feature 2: migrations/20261016_feature2_exam_foundation.sql; không chạy lại file này.
-- CREATE TABLE IF NOT EXISTS trong file khởi tạo không nâng cấp bảng đã tồn tại.
--
-- Design baseline:
--   - Follows the latest AIVES Conceptual ERD / Class Diagram decisions.
--   - Feature Group 1: Question Bank & Rubric / Learning Material & RAG
--   - Feature Group 3: AI-powered Viva Exam
--   - Feature Group 4: AI-assisted Grading
--
-- Authentication:
--   - Keycloak REMOVED.
--   - Phase 1: username/email + password; Spring Boot issues AIVES JWT.
--   - Refresh tokens are managed by refresh_tokens (stored as hashes).
--   - Phase 2: Google OAuth2 login maps directly through users.google_sub.
--   - No separate OAuth-account table is used for the current Google-only scope.
--
-- Database stack:
--   - PostgreSQL / Supabase PostgreSQL
--   - pgvector for RAG embeddings
--   - Supabase Storage for files/audio
--
-- IMPORTANT:
--   Supabase already provisions the PostgreSQL database itself, so CREATE
--   DATABASE must NOT be executed in the Supabase SQL editor.
--
--   For self-hosted/local PostgreSQL only, create the database first:
--       CREATE DATABASE aives_db;
--   Then connect to aives_db and run the remainder of this file.
-- ============================================================


-- ============================================================
-- 0. EXTENSIONS & APPLICATION SCHEMA
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS aives;

-- "extensions" is included because Supabase may install pgvector there.
-- PostgreSQL safely ignores a nonexistent schema in search_path.
SET search_path TO aives, public, extensions;


-- ============================================================
-- 1. MASTER DATA
-- ============================================================

-- Conceptual/Class Diagram entity: ROLES
CREATE TABLE IF NOT EXISTS roles (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description TEXT,

    CONSTRAINT chk_role_code
        CHECK (code IN ('ADMIN', 'LECTURER', 'STUDENT'))
);

-- Initial application roles.
INSERT INTO roles (code, name, description)
VALUES
    ('ADMIN', 'Administrator', 'System administrator'),
    ('LECTURER', 'Lecturer', 'Lecturer who manages materials, questions and viva exams'),
    ('STUDENT', 'Student', 'Student who takes viva exams')
ON CONFLICT (code) DO NOTHING;


-- Conceptual/Class Diagram entity: USERS
CREATE TABLE IF NOT EXISTS users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    role_id BIGINT NOT NULL
        REFERENCES roles(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,

    -- Nullable intentionally:
    -- Phase 1 application-created users use a password hash.
    -- Phase 2 may allow Google-authenticated accounts without a local password.
    password_hash VARCHAR(255),

    -- Stable Google OpenID Connect subject ("sub").
    -- NULL for users that have not linked/signed in with Google.
    -- PostgreSQL UNIQUE permits multiple NULL values.
    google_sub VARCHAR(255) UNIQUE,

    full_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Access JWTs are intentionally NOT persisted here.
-- They are short-lived and validated by the application/security layer.


-- Technical authentication support table required for AIVES refresh-token
-- lifecycle management. It is not a separate business/domain entity.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    user_id BIGINT NOT NULL
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    -- Store only a one-way hash of the refresh token, never the raw token.
    token_hash VARCHAR(255) NOT NULL UNIQUE,

    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_refresh_token_expiry
        CHECK (expires_at > created_at),

    CONSTRAINT chk_refresh_token_revoked_at
        CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);


-- Conceptual/Class Diagram entity: SUBJECTS
CREATE TABLE IF NOT EXISTS subjects (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT
);


-- ============================================================
-- 2. LEARNING MATERIAL & RAG
-- ============================================================

-- Conceptual/Class Diagram entity: LEARNING_MATERIALS
CREATE TABLE IF NOT EXISTS learning_materials (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    subject_id BIGINT NOT NULL
        REFERENCES subjects(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    -- Latest Class Diagram: each LearningMaterial has exactly one uploader.
    uploaded_by BIGINT NOT NULL
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    title VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    mime_type VARCHAR(100),

    processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (processing_status IN (
            'PENDING',
            'PROCESSING',
            'READY',
            'FAILED'
        )),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- Persistent/technical RAG class: LearningMaterialChunk
-- Not shown as an independent business entity in the Conceptual ERD.
CREATE TABLE IF NOT EXISTS learning_material_chunks (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    learning_material_id BIGINT NOT NULL
        REFERENCES learning_materials(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    chunk_index INT NOT NULL
        CHECK (chunk_index >= 0),

    content TEXT NOT NULL,

    -- Current embedding baseline: text-embedding-3-small -> 1536 dimensions.
    embedding VECTOR(1536),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_learning_material_chunk
        UNIQUE (learning_material_id, chunk_index)
);


-- ============================================================
-- 3. QUESTION BANK & RUBRIC
-- ============================================================
--
-- Core relationships:
--   SUBJECT  1 ---- 0..N QUESTION
--   USER     1 ---- 0..N QUESTION
--   QUESTION 1 ---- 0..1 RUBRIC
--   RUBRIC   1 ---- 1..N RUBRIC_CRITERIA (minimum 1 enforced by app flow)
-- ============================================================

-- Conceptual/Class Diagram entity: QUESTIONS
CREATE TABLE IF NOT EXISTS questions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    subject_id BIGINT NOT NULL
        REFERENCES subjects(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    -- Latest Class Diagram: each Question has exactly one creator.
    created_by BIGINT NOT NULL
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    content TEXT NOT NULL,

    bloom_level VARCHAR(20)
        CHECK (bloom_level IS NULL OR bloom_level IN (
            'REMEMBER',
            'UNDERSTAND',
            'APPLY',
            'ANALYZE',
            'EVALUATE',
            'CREATE'
        )),

    is_ai_generated BOOLEAN NOT NULL DEFAULT FALSE,
    is_approved BOOLEAN NOT NULL DEFAULT FALSE,

    reference_answer TEXT,
    answer_time_limit_seconds INT CHECK (answer_time_limit_seconds > 0),
    -- Cho phép NULL để tương thích dữ liệu cũ chưa phân loại trong migration.
    source_type VARCHAR(20) DEFAULT 'MANUAL'
        CHECK (source_type IN ('MANUAL', 'IMPORT', 'AI')),
    status VARCHAR(20) DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    updated_by BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Chủ đề là giá trị chuỗi của câu hỏi, chưa có danh mục Topic riêng.
CREATE TABLE IF NOT EXISTS question_topics (
    question_id BIGINT NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    topic VARCHAR(100) NOT NULL CHECK (btrim(topic) <> ''),
    PRIMARY KEY (question_id, topic)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_question_topics_normalized
    ON question_topics(question_id, lower(topic));

-- Quyền quản lý câu hỏi theo phân công giảng viên–môn học.
CREATE TABLE IF NOT EXISTS lecturer_subject_assignments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lecturer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    subject_id BIGINT NOT NULL REFERENCES subjects(id) ON DELETE RESTRICT,
    CONSTRAINT uq_lecturer_subject_assignment UNIQUE (lecturer_id, subject_id)
);
CREATE INDEX IF NOT EXISTS idx_assignment_subject ON lecturer_subject_assignments(subject_id);

-- Giữ nhật ký sau khi xóa bản nháp nên question_id không có khóa ngoại.
CREATE TABLE IF NOT EXISTS question_audit_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action VARCHAR(30) NOT NULL CHECK (action IN ('CREATED', 'UPDATED', 'DELETED')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_question_audit_question ON question_audit_logs(question_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_question_subject_status ON questions(subject_id, status, created_at, id);
CREATE INDEX IF NOT EXISTS idx_question_creator ON questions(created_by, created_at, id);


-- Conceptual/Class Diagram entity: RUBRICS
CREATE TABLE IF NOT EXISTS rubrics (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- UNIQUE enforces Subject 1 ---- 0..1 Rubric.
    subject_id BIGINT NOT NULL UNIQUE
        REFERENCES subjects(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    title VARCHAR(255),
    description TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);



-- Conceptual/Class Diagram entity: RUBRIC_CRITERIA
CREATE TABLE IF NOT EXISTS rubric_criteria (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    rubric_id BIGINT NOT NULL
        REFERENCES rubrics(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    criterion_text TEXT NOT NULL,

    point_weight NUMERIC(5,2) NOT NULL
        CHECK (point_weight >= 0),

    is_mandatory BOOLEAN NOT NULL DEFAULT FALSE,

    display_order INT NOT NULL DEFAULT 1
        CHECK (display_order > 0),

    CONSTRAINT uq_rubric_criterion_order
        UNIQUE (rubric_id, display_order)
);


-- ============================================================
-- 4. AI VIVA EXAM
-- ============================================================
--
-- Core relationships:
--   USER (Lecturer) 1 ---- 0..N EXAM_SESSION
--   SUBJECT         1 ---- 0..N EXAM_SESSION
--   EXAM_SESSION    1 ---- 0..N STUDENT_EXAM
--   USER (Student)  1 ---- 0..N STUDENT_EXAM
--
-- Role-specific rules (lecturer_id must be LECTURER, student_id must be
-- STUDENT) are enforced in the application/service layer.
-- ============================================================

-- Conceptual/Class Diagram entity: EXAM_SESSIONS
CREATE TABLE IF NOT EXISTS exam_sessions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    lecturer_id BIGINT NOT NULL
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    subject_id BIGINT NOT NULL
        REFERENCES subjects(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    title VARCHAR(255) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'UPCOMING'
        CHECK (status IN (
            'UPCOMING',
            'ONGOING',
            'COMPLETED'
        )),

    max_main_questions INT NOT NULL DEFAULT 3
        CHECK (max_main_questions > 0),

    max_follow_ups INT NOT NULL DEFAULT 2
        CHECK (max_follow_ups >= 0),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Nullable cho dữ liệu cũ; service ở bước sau yêu cầu lịch cho phiên mới.
    scheduled_start_at TIMESTAMPTZ,
    scheduled_end_at TIMESTAMPTZ,
    slot_duration_minutes INTEGER,

    CONSTRAINT ck_exam_session_schedule CHECK (
        (scheduled_start_at IS NULL AND scheduled_end_at IS NULL)
        OR (scheduled_start_at IS NOT NULL AND scheduled_end_at IS NOT NULL
            AND scheduled_end_at > scheduled_start_at)
    ),
    CONSTRAINT ck_exam_session_slot_duration
        CHECK (slot_duration_minutes IS NULL OR slot_duration_minutes > 0)
);


-- Conceptual/Class Diagram entity: STUDENT_EXAMS
CREATE TABLE IF NOT EXISTS student_exams (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    session_id BIGINT NOT NULL
        REFERENCES exam_sessions(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    student_id BIGINT NOT NULL
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    status VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED'
        CHECK (status IN (
            'ASSIGNED',
            'IN_PROGRESS',
            'COMPLETED',
            'ABSENT',
            'EXPIRED'
        )),

    -- Feature Group 4: AI-assisted grading.
    ai_suggested_score NUMERIC(5,2),
    final_score NUMERIC(5,2),
    ai_feedback TEXT,

    -- Optional backup recording stored in Supabase Storage.
    audio_record_url VARCHAR(500),

    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,

    -- Lịch dự kiến tách biệt thời gian thực tế; không tự suy diễn cho bản ghi cũ.
    slot_order INTEGER,
    scheduled_start_at TIMESTAMPTZ,
    scheduled_end_at TIMESTAMPTZ,

    CONSTRAINT ck_student_exam_slot_order CHECK (slot_order IS NULL OR slot_order > 0),
    CONSTRAINT ck_student_exam_schedule CHECK (
        (scheduled_start_at IS NULL AND scheduled_end_at IS NULL)
        OR (scheduled_start_at IS NOT NULL AND scheduled_end_at IS NOT NULL
            AND scheduled_end_at > scheduled_start_at)
    ),
    CONSTRAINT uq_student_exam_slot_order UNIQUE (session_id, slot_order),

    CONSTRAINT uq_exam_session_student
        UNIQUE (session_id, student_id),

    CONSTRAINT chk_ai_suggested_score
        CHECK (
            ai_suggested_score IS NULL
            OR ai_suggested_score BETWEEN 0 AND 10
        ),

    CONSTRAINT chk_final_score
        CHECK (
            final_score IS NULL
            OR final_score BETWEEN 0 AND 10
        ),

    CONSTRAINT chk_student_exam_time
        CHECK (
            completed_at IS NULL
            OR started_at IS NULL
            OR completed_at >= started_at
        )
);


-- Associative entity/class: STUDENT_EXAM_QUESTIONS
CREATE TABLE IF NOT EXISTS student_exam_questions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    student_exam_id BIGINT NOT NULL
        REFERENCES student_exams(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    question_id BIGINT NOT NULL
        REFERENCES questions(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    question_order INT NOT NULL
        CHECK (question_order > 0),

    CONSTRAINT uq_student_exam_question
        UNIQUE (student_exam_id, question_id),

    CONSTRAINT uq_student_exam_question_order
        UNIQUE (student_exam_id, question_order)
);


-- ============================================================
-- 5. VIVA CONVERSATION / TRANSCRIPT
-- ============================================================
--
-- Conceptual entity/table: TRANSCRIPTS
-- Class Diagram class: TranscriptTurn
--
-- TRANSCRIPT links only to STUDENT_EXAM_QUESTION.
-- It does NOT duplicate direct foreign keys to STUDENT_EXAMS,
-- QUESTIONS or RUBRICS.
--
-- From a transcript turn:
--   TRANSCRIPT
--      -> STUDENT_EXAM_QUESTION
--      -> STUDENT_EXAM
--      -> EXAM_SESSION / STUDENT
--
-- and:
--   TRANSCRIPT
--      -> STUDENT_EXAM_QUESTION
--      -> QUESTION
--      -> RUBRIC
--      -> RUBRIC_CRITERIA
-- ============================================================

CREATE TABLE IF NOT EXISTS transcripts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    student_exam_question_id BIGINT NOT NULL
        REFERENCES student_exam_questions(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    turn_no INT NOT NULL
        CHECK (turn_no > 0),

    turn_type VARCHAR(20) NOT NULL
        CHECK (turn_type IN (
            'AI_MAIN_Q',
            'STUDENT_ANS',
            'AI_FOLLOW_UP'
        )),

    content TEXT NOT NULL,

    -- Optional temporary AI evaluation for a STUDENT_ANS turn.
    ai_eval_score NUMERIC(5,2),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_question_conversation_turn
        UNIQUE (student_exam_question_id, turn_no),

    CONSTRAINT chk_transcript_ai_eval_score
        CHECK (
            ai_eval_score IS NULL
            OR ai_eval_score BETWEEN 0 AND 10
        )
);

-- Bật bảo vệ theo dòng cho các bảng nền móng Question CRUD.
ALTER TABLE aives.question_topics ENABLE ROW LEVEL SECURITY;
ALTER TABLE aives.lecturer_subject_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE aives.question_audit_logs ENABLE ROW LEVEL SECURITY;


-- ============================================================
-- 6. INDEXES
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_users_role_id
    ON users(role_id);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id
    ON refresh_tokens(user_id);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at
    ON refresh_tokens(expires_at);

CREATE INDEX IF NOT EXISTS idx_learning_materials_subject_id
    ON learning_materials(subject_id);

CREATE INDEX IF NOT EXISTS idx_learning_materials_uploaded_by
    ON learning_materials(uploaded_by);

CREATE INDEX IF NOT EXISTS idx_learning_material_chunks_material_id
    ON learning_material_chunks(learning_material_id);

CREATE INDEX IF NOT EXISTS idx_questions_subject_id
    ON questions(subject_id);

CREATE INDEX IF NOT EXISTS idx_questions_created_by
    ON questions(created_by);

-- rubrics(question_id) already has an index through UNIQUE(question_id).

CREATE INDEX IF NOT EXISTS idx_rubric_criteria_rubric_id
    ON rubric_criteria(rubric_id);

CREATE INDEX IF NOT EXISTS idx_exam_sessions_lecturer_id
    ON exam_sessions(lecturer_id);

CREATE INDEX IF NOT EXISTS idx_exam_sessions_subject_id
    ON exam_sessions(subject_id);

CREATE INDEX IF NOT EXISTS idx_student_exams_session_id
    ON student_exams(session_id);

CREATE INDEX IF NOT EXISTS idx_student_exams_student_id
    ON student_exams(student_id);

CREATE INDEX IF NOT EXISTS idx_student_exam_questions_exam_id
    ON student_exam_questions(student_exam_id);

CREATE INDEX IF NOT EXISTS idx_student_exam_questions_question_id
    ON student_exam_questions(question_id);

CREATE INDEX IF NOT EXISTS idx_transcripts_student_exam_question_id
    ON transcripts(student_exam_question_id);


-- ============================================================
-- 7. OPTIONAL VECTOR INDEX
-- ============================================================
--
-- Create/tune the vector index after enough embedding data exists.
-- Example using HNSW + cosine distance:
--
-- CREATE INDEX idx_learning_material_chunks_embedding_hnsw
--     ON learning_material_chunks
--     USING hnsw (embedding vector_cosine_ops);
--
-- ============================================================
-- END OF AIVES DATABASE INITIALIZATION
-- ============================================================

-- Snapshot lịch sử Feature 2: đồng bộ với 20261025_feature2_exam_snapshots.sql.
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

-- Feature 3: cùng định nghĩa với migrations/20261028_feature3_oral_progress.sql.
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

-- Feature 3 / STT audio metadata (2026-10-29)
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


-- Feature 3 / AI decision claims (2026-10-29)
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
