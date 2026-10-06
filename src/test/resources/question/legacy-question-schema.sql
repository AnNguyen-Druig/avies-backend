-- Fixture trước migration: chỉ các bảng cần cho Question và xác thực, không chứa dữ liệu thật.
-- Chỉ được thực thi trong container PostgreSQL tạm của bộ test.
CREATE SCHEMA aives;
CREATE TABLE aives.roles (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description TEXT
);
CREATE TABLE aives.users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_id BIGINT NOT NULL REFERENCES aives.roles(id),
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    google_sub VARCHAR(255) UNIQUE,
    full_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE aives.subjects (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT
);
CREATE TABLE aives.questions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id BIGINT NOT NULL REFERENCES aives.subjects(id) ON DELETE CASCADE,
    created_by BIGINT NOT NULL REFERENCES aives.users(id) ON DELETE RESTRICT,
    content TEXT NOT NULL,
    bloom_level VARCHAR(20),
    is_ai_generated BOOLEAN NOT NULL DEFAULT TRUE,
    is_approved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE aives.rubrics (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_id BIGINT NOT NULL UNIQUE REFERENCES aives.questions(id) ON DELETE CASCADE,
    title VARCHAR(255), description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE aives.rubric_criteria (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rubric_id BIGINT NOT NULL REFERENCES aives.rubrics(id) ON DELETE CASCADE,
    criterion_text TEXT NOT NULL, point_weight NUMERIC(5,2) NOT NULL,
    is_mandatory BOOLEAN NOT NULL DEFAULT FALSE, display_order INTEGER NOT NULL DEFAULT 1
);
-- Chỉ mô phỏng quan hệ bảo vệ câu hỏi; không mô phỏng toàn bộ nghiệp vụ phiên thi.
CREATE TABLE aives.student_exam_questions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES aives.questions(id)
);
CREATE TABLE aives.invalidated_tokens (
    id VARCHAR(255) PRIMARY KEY, expiry_time TIMESTAMP NOT NULL
);
