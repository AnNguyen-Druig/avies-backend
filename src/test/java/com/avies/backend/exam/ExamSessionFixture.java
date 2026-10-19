package com.avies.backend.exam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fixture chỉ nhận datasource Docker từ test config; không dùng URL hoặc dữ liệu thật. */
final class ExamSessionFixture {
    /** JDBC trong container do suite sở hữu. */
    final JdbcTemplate jdbc;
    /** Mốc tương lai cố định trong mỗi ca, có offset +07 để kiểm tra quy đổi thời gian. */
    final OffsetDateTime start = OffsetDateTime.now(java.time.ZoneOffset.ofHours(7)).plusDays(7).truncatedTo(ChronoUnit.MINUTES);

    /** Tạo fixture trên connection test đã được kiểm soát. */
    ExamSessionFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Chỉ reset dữ liệu trong container này; schema/constraints vẫn giữ nguyên. */
    void reset() {
        jdbc.execute("TRUNCATE aives.roles, aives.subjects RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO aives.roles(code,name) VALUES ('ADMIN','Admin'),('LECTURER','Lecturer'),('STUDENT','Student')");
        for (String username : new String[]{"owner", "other", "outsider", "student", "admin"}) {
            int role = username.equals("admin") ? 1 : username.equals("student") ? 3 : 2;
            jdbc.update("INSERT INTO aives.users(role_id,username,email,full_name) VALUES (?,?,?,?)",
                    role, username, username + "@test.invalid", username);
        }
        jdbc.update("INSERT INTO aives.subjects(code,name) VALUES ('SWD392','Kiến trúc phần mềm'),('OTHER','Môn khác')");
        jdbc.update("INSERT INTO aives.lecturer_subject_assignments(lecturer_id,subject_id) VALUES (1,1),(1,2),(2,1)");
    }

    /** Request tạo đầy đủ, không giả định ID từ Supabase. */
    String creation() {
        return """
                {"title":"  Phiên thi mới  ","subjectId":1,"scheduledStartAt":"%s","scheduledEndAt":"%s",
                "slotDurationMinutes":20,"maxMainQuestions":3,"maxFollowUps":0}
                """.formatted(start, start.plusHours(2));
    }

    /** Tạo phiên qua endpoint thật và trả ID do database sinh. */
    long create(MockMvc mvc) throws Exception {
        String body = mvc.perform(as(post("/exam-sessions").contentType(MediaType.APPLICATION_JSON).content(creation()), "owner"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.result.id")).longValue();
    }

    /** Tạo phiên kiểm tra ở một trạng thái xác định; chỉ dùng dữ liệu giả trong container. */
    long session(long lecturerId, long subjectId, String status) {
        return jdbc.queryForObject("INSERT INTO aives.exam_sessions(lecturer_id,subject_id,title,status,scheduled_start_at,scheduled_end_at,slot_duration_minutes) "
                        + "VALUES (?,?,'Fixture',?,?,?,20) RETURNING id", Long.class,
                lecturerId, subjectId, status, start, start.plusHours(2));
    }

    /** Tạo bài thi sinh viên để thử các lớp bảo vệ lịch sử, không triển khai API assignment. */
    long studentExam(long sessionId, String status) {
        return jdbc.queryForObject("INSERT INTO aives.student_exams(session_id,student_id,status) VALUES (?,4,?) RETURNING id",
                Long.class, sessionId, status);
    }

    /** Gắn principal theo tài khoản fixture; có thể thay authority để thử token đã cũ. */
    static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, String username) {
        String role = username.equals("admin") ? "ADMIN" : username.equals("student") ? "STUDENT" : "LECTURER";
        return request.with(jwt().jwt(token -> token.subject(username)).authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
