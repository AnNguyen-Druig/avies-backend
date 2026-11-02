package com.avies.backend.service.support;

import java.util.List;
import java.util.UUID;

/** Context nội bộ của đúng câu snapshot; chứa căn cứ hỏi sâu, tuyệt đối không serialize cho Student. */
public record OralDecisionWork(
        /** Lượt thi giữ khóa/claim. */ long examId,
        /** Submission đã có transcript thật. */ UUID submissionId,
        /** Nonce chống callback cũ và worker trùng. */ UUID claimId,
        /** Ngôn ngữ đã xác định cho câu chính. */ String language,
        /** Câu chính đã snapshot, không đọc question bank đang sửa. */ String mainPrompt,
        /** Các lượt gần nhất của cùng câu chính, gồm câu trả lời vừa được lưu. */ List<Exchange> history,
        /** BE quyết định còn quota và ngân sách để hỏi thêm hay không. */ boolean canFollowUp,
        /** Chỉ provider quyết định được đọc; không chuyển sang TTS, STT hoặc DTO Student. */ Assessment assessment) {
    /** Không cho provider sửa danh sách context. */ public OralDecisionWork { history = List.copyOf(history); java.util.Objects.requireNonNull(assessment); }
    /** Chỉ văn bản snapshot cần để đối chiếu ý, không gửi ID, điểm hoặc danh tính ra nhà cung cấp. */
    public record Assessment(String referenceAnswer, String rubricTitle, String rubricDescription, List<String> criteria) {
        /** Giữ danh sách bất biến, đáp án/miêu tả null vẫn hợp lệ ở dữ liệu nguồn. */
        public Assessment { criteria = List.copyOf(criteria); }
        /** Không cần đọc rubric khi BE không cho hỏi thêm. */
        public static Assessment empty() { return new Assessment(null,null,null,List.of()); }
    }
    /** Một lượt hỏi/đáp đã được ghi nhận trên máy chủ. */
    public record Exchange(/** Prompt đã phát. */ String question, /** Transcript thật. */ String answer) { }
}
