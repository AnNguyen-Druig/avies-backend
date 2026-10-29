package com.avies.backend.service.support;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

/** Kế hoạch nội bộ bất biến, tuyệt đối không serialize nội dung đề ra HTTP. */
public record OralSpeechPlan(UUID turnId, String language, String text) {
    /** Câu Việt có dấu dùng vi; câu Anh dùng en, follow-up kế thừa ngôn ngữ đã chốt. */
    public static OralSpeechPlan from(UUID id, String inherited, int order, int followUp, String prompt) {
        String normalized = Normalizer.normalize(prompt, Normalizer.Form.NFC);
        String language = inherited != null ? inherited : normalized.toLowerCase(Locale.ROOT).matches("(?s).*[ăâđêôơưàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ].*") ? "vi" : "en";
        String prefix = "vi".equals(language) ? "Câu số " + order + (followUp > 0 ? ", câu hỏi đào sâu " + followUp : "") + ". "
                : "Question number " + order + (followUp > 0 ? ", follow-up " + followUp : "") + ". ";
        return new OralSpeechPlan(id, language, prefix + normalized);
    }
}
