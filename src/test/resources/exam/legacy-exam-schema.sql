-- Schema thi trước Feature 2 theo SQL repository/P0.1; chỉ dùng trong database Docker tạm.
-- Không có sáu cột lịch mới. Không suy ra đây là toàn bộ metadata của Supabase thực tế.
CREATE TABLE aives.exam_sessions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lecturer_id BIGINT NOT NULL REFERENCES aives.users(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    subject_id BIGINT NOT NULL REFERENCES aives.subjects(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'UPCOMING' CHECK (status IN ('UPCOMING', 'ONGOING', 'COMPLETED')),
    max_main_questions INTEGER NOT NULL DEFAULT 3 CHECK (max_main_questions > 0),
    max_follow_ups INTEGER NOT NULL DEFAULT 2 CHECK (max_follow_ups >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE aives.student_exams (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES aives.exam_sessions(id) ON UPDATE CASCADE ON DELETE CASCADE,
    student_id BIGINT NOT NULL REFERENCES aives.users(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED' CHECK (status IN ('ASSIGNED', 'IN_PROGRESS', 'COMPLETED')),
    ai_suggested_score NUMERIC(5,2), final_score NUMERIC(5,2), ai_feedback TEXT,
    audio_record_url VARCHAR(500), started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
    CONSTRAINT uq_exam_session_student UNIQUE (session_id, student_id),
    CONSTRAINT chk_ai_suggested_score CHECK (ai_suggested_score IS NULL OR ai_suggested_score BETWEEN 0 AND 10),
    CONSTRAINT chk_final_score CHECK (final_score IS NULL OR final_score BETWEEN 0 AND 10),
    CONSTRAINT chk_student_exam_time CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
);
CREATE TABLE aives.student_exam_questions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_exam_id BIGINT NOT NULL REFERENCES aives.student_exams(id) ON UPDATE CASCADE ON DELETE CASCADE,
    question_id BIGINT NOT NULL REFERENCES aives.questions(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    question_order INTEGER NOT NULL CHECK (question_order > 0),
    CONSTRAINT uq_student_exam_question UNIQUE (student_exam_id, question_id),
    CONSTRAINT uq_student_exam_question_order UNIQUE (student_exam_id, question_order)
);
CREATE INDEX idx_exam_sessions_lecturer_id ON aives.exam_sessions(lecturer_id);
CREATE INDEX idx_exam_sessions_subject_id ON aives.exam_sessions(subject_id);
CREATE INDEX idx_student_exams_session_id ON aives.student_exams(session_id);
CREATE INDEX idx_student_exams_student_id ON aives.student_exams(student_id);
CREATE INDEX idx_student_exam_questions_exam_id ON aives.student_exam_questions(student_exam_id);
CREATE INDEX idx_student_exam_questions_question_id ON aives.student_exam_questions(question_id);

-- Dữ liệu giả có trước migration: trạng thái/điểm/thời gian thực tế phải được bảo toàn.
INSERT INTO aives.roles(code, name) VALUES ('LECTURER', 'Lecturer'), ('STUDENT', 'Student');
INSERT INTO aives.users(role_id, username, email, full_name)
VALUES (1, 'legacy-lecturer', 'lecturer@test.invalid', 'Giảng viên cũ'),
       (2, 'legacy-student', 'student@test.invalid', 'Sinh viên cũ');
INSERT INTO aives.subjects(code, name) VALUES ('SWD392', 'Kiến trúc phần mềm');
INSERT INTO aives.questions(subject_id, created_by, content, is_ai_generated, is_approved)
VALUES (1, 1, 'Câu hỏi giữ nguyên', false, false);
INSERT INTO aives.exam_sessions(lecturer_id, subject_id, title, status, created_at)
VALUES (1, 1, 'Phiên thi cũ', 'COMPLETED', '2026-10-01T01:00:00Z');
INSERT INTO aives.student_exams(session_id, student_id, status, ai_suggested_score, final_score,
    ai_feedback, audio_record_url, started_at, completed_at)
VALUES (1, 2, 'COMPLETED', 7.25, 8.50, 'Giữ nhận xét', 'demo/record.wav',
    '2026-10-01T02:00:00Z', '2026-10-01T02:15:00Z');
INSERT INTO aives.student_exam_questions(student_exam_id, question_id, question_order) VALUES (1, 1, 1);
