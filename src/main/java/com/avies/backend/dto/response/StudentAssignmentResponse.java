package com.avies.backend.dto.response;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

/** Kết quả batch nguyên tử: phân biệt bài vừa tạo với bài đã tồn tại được giữ nguyên. */
@Getter
@Builder
public class StudentAssignmentResponse {
    /** Phiên thi được thao tác. */
    private final Long sessionId;

    /** Bài mới theo thứ tự ID sinh viên duy nhất trong request. */
    private final List<StudentExamAssignmentResponse> created;

    /** Bài đã có, giữ nguyên ID, lịch và trạng thái. */
    private final List<StudentExamAssignmentResponse> unchanged;
}
