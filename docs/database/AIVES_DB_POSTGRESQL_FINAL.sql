-- ============================================================
-- AIVES Database Initialization - PostgreSQL / Supabase
-- Version: 2026-10-05 (Question CRUD - đề xuất, chưa áp dụng database)
-- Database đã tồn tại: dùng migrations/20261005_question_crud.sql sau khi được duyệt.
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
            'ANALYZE'
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

    -- UNIQUE enforces Question 1 ---- 0..1 Rubric.
    question_id BIGINT NOT NULL UNIQUE
        REFERENCES questions(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    title VARCHAR(255),
    description TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
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

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
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
            'COMPLETED'
        )),

    -- Feature Group 4: AI-assisted grading.
    ai_suggested_score NUMERIC(5,2),
    final_score NUMERIC(5,2),
    ai_feedback TEXT,

    -- Optional backup recording stored in Supabase Storage.
    audio_record_url VARCHAR(500),

    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,

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
