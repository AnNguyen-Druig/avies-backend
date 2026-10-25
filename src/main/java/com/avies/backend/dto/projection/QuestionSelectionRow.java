package com.avies.backend.dto.projection;

import java.math.BigDecimal;

/** Dòng scalar đọc trực tiếp từ DB; không phụ thuộc entity đã cache trong persistence context. */
public interface QuestionSelectionRow {
    /** ID câu hỏi gốc. */ Long getQuestionId();
    /** Môn của câu hỏi tại thời điểm query. */ Long getSubjectId();
    /** Version dùng phát hiện câu đã đổi giữa lần đọc và lần khóa. */ Long getQuestionVersion();
    /** Trạng thái nguồn, chưa ép sang enum để loại dữ liệu không hỗ trợ một cách an toàn. */ String getQuestionStatus();
    /** Tên Bloom hiện lưu trong database. */ String getBloomLevel();
    /** Câu đã lưu trữ không còn được chọn mới. */ Boolean getArchived();
    /** Nội dung dùng kiểm tra đầy đủ, không trả qua DTO selection. */ String getContent();
    /** Đáp án nguồn để lưu cùng snapshot câu, không trả cho sinh viên. */ String getReferenceAnswer();
    /** Giới hạn trả lời nguyên bản, cho phép chưa được cấu hình. */ Integer getAnswerTimeLimitSeconds();
    /** Rubric hiện hành của môn, null nếu thiếu; không thay thế rubric đã chốt của phiên. */ Long getRubricId();
    /** ID tiêu chí, null nếu rubric rỗng. */ Long getCriterionId();
    /** Mô tả tiêu chí chấm, chỉ dùng nội bộ để kiểm tra. */ String getCriterionText();
    /** Điểm tối đa của tiêu chí. */ BigDecimal getPointWeight();
    /** Cờ được giữ nguyên; selection không suy diễn cơ chế trượt cả câu. */ Boolean getMandatory();
    /** Thứ tự tiêu chí dương và duy nhất trong rubric. */ Integer getDisplayOrder();
}
