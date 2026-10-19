package com.avies.backend.dto.request;

import com.avies.backend.entity.enums.ExamSessionStatus;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/** Bộ lọc phiên thi; service luôn bổ sung phạm vi chủ sở hữu trước phân trang. */
@Getter
@Setter
public class ExamSessionSearchRequest {
    /** Trang bắt đầu từ không, cùng giới hạn offset với Question. */
    @Min(0)
    @Max(1000000)
    private int page;

    /** Kích thước mặc định 20, tối đa 100. */
    @Min(1)
    @Max(100)
    private int size = 20;

    /** Môn cần lọc; không mở rộng phạm vi quyền. */
    @Positive
    private Long subjectId;

    /** Trạng thái cần lọc, chỉ dùng ba giá trị enum hiện có. */
    private ExamSessionStatus status;
}
