package com.avies.backend.service.support;

import java.util.List;
import java.util.UUID;

/** Context nội bộ của đúng câu snapshot; không chứa đáp án, rubric hoặc danh tính Student. */
public record OralDecisionWork(
        /** Lượt thi giữ khóa/claim. */ long examId,
        /** Submission đã có transcript thật. */ UUID submissionId,
        /** Nonce chống callback cũ và worker trùng. */ UUID claimId,
        /** Ngôn ngữ đã xác định cho câu chính. */ String language,
        /** Câu chính đã snapshot, không đọc question bank đang sửa. */ String mainPrompt,
        /** Các lượt gần nhất của cùng câu chính, gồm câu trả lời vừa được lưu. */ List<Exchange> history,
        /** BE quyết định còn quota và ngân sách để hỏi thêm hay không. */ boolean canFollowUp) {
    /** Không cho provider sửa danh sách context. */ public OralDecisionWork { history = List.copyOf(history); }
    /** Một lượt hỏi/đáp đã được ghi nhận trên máy chủ. */
    public record Exchange(/** Prompt đã phát. */ String question, /** Transcript thật. */ String answer) { }
}
