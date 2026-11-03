package com.avies.backend.service.support;

import com.avies.backend.exception.AppException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import static org.assertj.core.api.Assertions.*;

class AzureSpeechSsmlTest {
    private static final String VOICE = "en-US-AvaMultilingualNeural";
    private static final String NS = "http://www.w3.org/2001/10/synthesis";
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
    private Document parse(String ssml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(ssml)));
    }
    @Test void marksUseCaseAsEnglishButPreservesTheExactOriginalQuestion() throws Exception {
        String text = "Câu số 1. Bạn hãy nêu lí do tại sao phải đặt tên các use case khác nhau?";
        var document = parse(AzureSpeechSsml.render(text,"vi",VOICE));
        assertThat(document.getDocumentElement().getTextContent()).isEqualTo(text);
        var voice = (org.w3c.dom.Element)document.getElementsByTagNameNS(NS,"voice").item(0);
        assertThat(voice.getAttribute("name")).isEqualTo(VOICE);
        var spans = document.getElementsByTagNameNS(NS,"lang"); assertThat(spans.getLength()).isEqualTo(3);
        assertThat(((org.w3c.dom.Element)spans.item(0)).getAttributeNS(XML_NS,"lang")).isEqualTo("vi-VN");
        assertThat(((org.w3c.dom.Element)spans.item(1)).getAttributeNS(XML_NS,"lang")).isEqualTo("en-US");
        assertThat(spans.item(1).getTextContent()).isEqualTo("use case");
    }
    @Test void matchesCasePluralHyphenAndLongestTermsWithoutMatchingInsideOtherWords() throws Exception {
        String text = "USE CASE DIAGRAMS, use cases, use-case, UseCase và Design Pattern. showcase interfaceX APIển.";
        var document = parse(AzureSpeechSsml.render(text,"vi",VOICE)); assertThat(document.getDocumentElement().getTextContent()).isEqualTo(text);
        var spans = document.getElementsByTagNameNS(NS,"lang"); var english = new java.util.ArrayList<String>();
        for (int i=0;i<spans.getLength();i++) {
            var span=(org.w3c.dom.Element)spans.item(i); if(span.getAttributeNS(XML_NS,"lang").equals("en-US")) english.add(span.getTextContent());
        }
        assertThat(english).containsExactly("USE CASE DIAGRAMS","use cases","use-case","UseCase","Design Pattern");
    }
    @Test void escapesUntrustedMarkupRatherThanAllowingVoiceAudioOrSsmlInjection() throws Exception {
        String text = "Bạn giải thích <audio src=\"https://example.invalid\">use case</audio> & ký tự > ' ?";
        var document = parse(AzureSpeechSsml.render(text,"vi",VOICE));
        assertThat(document.getDocumentElement().getTextContent()).isEqualTo(text);
        assertThat(document.getElementsByTagNameNS(NS,"audio").getLength()).isZero();
        assertThat(document.getElementsByTagNameNS(NS,"voice").getLength()).isEqualTo(1);
    }
    @Test void englishAndVietnameseOnlyTextKeepTheirLanguagesAndContent() throws Exception {
        for (String language: java.util.List.of("vi","en")) {
            String text = language.equals("vi") ? "Câu số 2. Em hãy giải thích mối quan hệ này." : "Question number 2. Why must use cases have different names?";
            var document = parse(AzureSpeechSsml.render(text,language,VOICE));
            var spans = document.getElementsByTagNameNS(NS,"lang"); assertThat(spans.getLength()).isEqualTo(1);
            assertThat(((org.w3c.dom.Element)spans.item(0)).getAttributeNS(XML_NS,"lang")).isEqualTo(language.equals("vi") ? "vi-VN" : "en-US");
            assertThat(document.getDocumentElement().getTextContent()).isEqualTo(text);
        }
    }
    @Test void explicitLegacyVoiceRemainsCompatibleWithoutUnsupportedLangElements() throws Exception {
        String text="Bạn giải thích use case?";
        var document = parse(AzureSpeechSsml.render(text,"vi","vi-VN-HoaiMyNeural"));
        assertThat(document.getElementsByTagNameNS(NS,"lang").getLength()).isZero();
        assertThat(document.getDocumentElement().getTextContent()).isEqualTo(text);
    }
    @Test void invalidXmlOversizedSsmlAndInvalidVoiceAreRejectedWithoutTruncating() {
        for (String text: java.util.List.of("bad\0text","bad\uD800text","x".repeat(11001),"API và ".repeat(1500)))
            assertThatThrownBy(()->AzureSpeechSsml.render(text,"vi",VOICE)).isInstanceOf(AppException.class);
        assertThatThrownBy(()->AzureSpeechSsml.render("Question?","fr",VOICE)).isInstanceOf(AppException.class);
        assertThatThrownBy(()->AzureSpeechSsml.render("Question?","vi","bad\" name=\"injected")).isInstanceOf(AppException.class);
    }
}
