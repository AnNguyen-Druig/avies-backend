package com.avies.backend.dto.response;

import java.util.UUID;

/** Chỉ câu đang trả lời đã nghe xong; không chứa đáp án, rubric hoặc các câu khác. */
public record OralQuestionTextResponse(UUID turnId, int questionOrder, int followUpIndex, String language, String text) { }
