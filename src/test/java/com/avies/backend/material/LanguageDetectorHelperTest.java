package com.avies.backend.material;

import com.avies.backend.service.support.LanguageDetectorHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDetectorHelperTest {

    @Test
    @DisplayName("Phát hiện văn bản tiếng Việt có dấu")
    void detectVietnameseWithAccents() {
        String text = "Chương 1: Tổng quan về kiến trúc phần mềm và các mô hình thiết kế hệ thống phân tán.";
        assertThat(LanguageDetectorHelper.detectLanguage(text)).isEqualTo(LanguageDetectorHelper.LANG_VI);
    }

    @Test
    @DisplayName("Phát hiện văn bản tiếng Anh")
    void detectEnglishText() {
        String text = "Software Architecture and Design Patterns. Chapter 1: Introduction to Clean Architecture and Domain-Driven Design.";
        assertThat(LanguageDetectorHelper.detectLanguage(text)).isEqualTo(LanguageDetectorHelper.LANG_EN);
    }

    @Test
    @DisplayName("Phát hiện văn bản tiếng Anh có header nhỏ tiếng Việt")
    void detectEnglishSlideWithVietnameseHeader() {
        String text = "Đại học FPT\n\nChapter 2: Microservices Architecture\n"
                + "In software engineering, microservices is an architectural style that structures an application as a collection of services that are highly maintainable, testable, loosely coupled, and independently deployable.";
        assertThat(LanguageDetectorHelper.detectLanguage(text)).isEqualTo(LanguageDetectorHelper.LANG_EN);
    }

    @Test
    @DisplayName("Văn bản null hoặc rỗng trả về mặc định tiếng Việt")
    void detectNullOrBlank() {
        assertThat(LanguageDetectorHelper.detectLanguage(null)).isEqualTo(LanguageDetectorHelper.LANG_VI);
        assertThat(LanguageDetectorHelper.detectLanguage("   ")).isEqualTo(LanguageDetectorHelper.LANG_VI);
    }
}

