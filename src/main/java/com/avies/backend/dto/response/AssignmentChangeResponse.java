package com.avies.backend.dto.response;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

/** Kết quả thay đổi phân công; gọi lặp có thể không làm thay đổi bản ghi nào. */
@Getter
@Builder
public class AssignmentChangeResponse {
    /** Giảng viên được xử lý trong yêu cầu. */
    private final Long lecturerId;
    /** Mã môn thực sự được thêm hoặc thu hồi trong lần gọi này, sắp xếp tăng dần. */
    private final List<Long> changedSubjectIds;
    /** Mã môn đã ở trạng thái mong muốn nên không cần thay đổi. */
    private final List<Long> unchangedSubjectIds;
}
