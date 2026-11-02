package com.avies.backend.service.support;

import lombok.extern.slf4j.Slf4j;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiện ích nhận diện ngôn ngữ (tiếng Việt / tiếng Anh) dựa trên text bóc tách từ tài liệu học tập.
 */
@Slf4j
public final class LanguageDetectorHelper {

    private LanguageDetectorHelper() {
        // Utility class
    }

    public static final String LANG_VI = "vi";
    public static final String LANG_EN = "en";
    public static final String LANG_AUTO = "AUTO";

    /**
     * Ký tự có dấu đặc trưng của tiếng Việt (NFC).
     */
    private static final Pattern VIETNAMESE_ACCENT_PATTERN = Pattern.compile(
            "[àáảãạâầấẩẫậăằắẳẵặèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    /**
     * Tập từ dừng / từ ngữ tiếng Việt xuất hiện với tần suất rất cao trong văn bản tiếng Việt.
     */
    private static final Set<String> VIETNAMESE_COMMON_WORDS = Set.of(
            "và", "của", "các", "những", "trong", "là", "với", "được", "người", "không",
            "cho", "về", "có", "khi", "này", "theo", "đã", "đang", "sẽ", "bài",
            "học", "môn", "chương", "phần", "tài", "liệu", "câu", "hỏi", "giáo", "trình",
            "sinh", "viên", "giảng", "phương", "pháp", "kết", "quả", "thực", "hiện"
    );

    /**
     * Tập từ dừng tiếng Anh phổ biến.
     */
    private static final Set<String> ENGLISH_COMMON_WORDS = Set.of(
            "the", "and", "in", "is", "of", "to", "that", "for", "with", "as",
            "on", "are", "by", "this", "from", "an", "be", "or", "which", "at",
            "chapter", "system", "architecture", "software", "design", "data"
    );

    /**
     * Phân tích văn bản và nhận diện ngôn ngữ (mặc định trả về "vi" nếu văn bản rỗng).
     *
     * @param text Nội dung văn bản
     * @return "vi" hoặc "en"
     */
    public static String detectLanguage(String text) {
        if (text == null || text.isBlank()) {
            return LANG_VI;
        }

        // Lấy mẫu tối đa 5000 ký tự đầu tiên để tối ưu hiệu năng
        String sample = text.length() > 5000 ? text.substring(0, 5000) : text;
        String normalized = Normalizer.normalize(sample, Normalizer.Form.NFC);

        // 1. Đếm ký tự dấu tiếng Việt
        Matcher matcher = VIETNAMESE_ACCENT_PATTERN.matcher(normalized);
        int accentCount = 0;
        while (matcher.find()) {
            accentCount++;
        }

        double accentRatio = (double) accentCount / Math.max(1, normalized.length());

        // 2. Thống kê từ vựng tiếng Việt và tiếng Anh
        String[] words = normalized.toLowerCase(Locale.ROOT).split("[\\s\\p{Punct}]+");
        int viWordCount = 0;
        int enWordCount = 0;
        for (String word : words) {
            if (VIETNAMESE_COMMON_WORDS.contains(word)) {
                viWordCount++;
            }
            if (ENGLISH_COMMON_WORDS.contains(word)) {
                enWordCount++;
            }
        }

        // Nếu số lượng từ dừng tiếng Anh áp đảo hoàn toàn và số dấu tiếng Việt rất ít (ví dụ header/tên trường)
        if (enWordCount >= 3 && enWordCount > viWordCount * 2 && accentCount < 10) {
            log.debug("Phát hiện ngôn ngữ 'en' do từ vựng tiếng Anh áp đảo (enWords={}, viWords={}, accentCount={})",
                    enWordCount, viWordCount, accentCount);
            return LANG_EN;
        }

        // Trong tiếng Việt chuẩn, ký tự dấu chiếm khoảng 2% - 6% tổng số ký tự.
        // Nếu có >= 10 dấu hoặc tỷ lệ dấu >= 0.8% thì chắc chắn là tiếng Việt.
        if (accentCount >= 10 || (accentCount >= 3 && accentRatio >= 0.008)) {
            log.debug("Phát hiện ngôn ngữ 'vi' theo số lượng ký tự dấu: {} (tỷ lệ: {})", accentCount, accentRatio);
            return LANG_VI;
        }

        if (viWordCount >= 3 || (accentCount > 0 && viWordCount >= 1)) {
            log.debug("Phát hiện ngôn ngữ 'vi' theo từ khóa phổ biến: {}, dấu: {}", viWordCount, accentCount);
            return LANG_VI;
        }

        // Nếu không có dấu tiếng Việt và không có từ vựng tiếng Việt -> Kết luận là 'en'
        log.debug("Phát hiện ngôn ngữ 'en' (accentCount={}, viWordCount={}, sampleLen={})",
                accentCount, viWordCount, normalized.length());
        return LANG_EN;
    }
}
