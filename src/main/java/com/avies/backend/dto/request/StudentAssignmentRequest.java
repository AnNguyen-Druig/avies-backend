package com.avies.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

/** Batch tối đa 100 ID; ID lặp được gộp theo thứ tự xuất hiện đầu tiên. */
@Getter
@Setter
public class StudentAssignmentRequest {
    /** Các user phải tồn tại và đang có role STUDENT; chỉ nhận JSON integer dương. */
    @NotEmpty @Size(max = 100)
    @JsonDeserialize(contentUsing = ExamSessionJson.Identifier.class)
    private List<@NotNull @Positive Long> studentIds;

    /** Không nhận ownership, lịch tự đặt hoặc field ngoài hợp đồng. */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Trường không được hỗ trợ trong yêu cầu gán sinh viên.");
    }
}
