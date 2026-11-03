package com.avies.backend.service.support;

import com.avies.backend.config.AzureSpeechTtsProperties;
import com.avies.backend.exception.*;
import javax.xml.stream.*;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Chỉ tạo lớp phát âm, không dịch/thay từ hoặc sửa snapshot. Mọi text đều được XML-escape. */
public final class AzureSpeechSsml {
    private static final String NS = "http://www.w3.org/2001/10/synthesis";
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
    private static final int MAX_SSML_BYTES = 64 * 1024;
    /** Danh sách thuật ngữ có chủ đích, không đoán rằng mọi từ Latin trong tiếng Việt đều là tiếng Anh. */
    private static final List<String> ENGLISH_TERMS = List.of(
            "use case diagrams", "use case diagram", "use cases", "use case", "use-cases", "use-case", "usecases", "usecase",
            "design patterns", "design pattern", "software architecture", "software design", "abstract factory", "factory method",
            "dependency injection", "dependency inversion", "single responsibility", "open closed", "liskov substitution", "interface segregation",
            "singleton", "builder", "prototype", "adapter", "facade", "decorator", "proxy", "observer", "strategy", "repository",
            "interfaces", "interface", "classes", "class", "objects", "object", "inheritance", "encapsulation", "polymorphism", "abstraction",
            "microservices", "microservice", "databases", "database", "frameworks", "framework", "backend", "frontend", "back-end", "front-end",
            "follow-ups", "follow-up", "follow ups", "follow up", "websockets", "websocket", "web sockets", "web socket", "streaming",
            "controllers", "controller", "services", "service", "requests", "request", "responses", "response", "rest api", "api",
            "sse", "https", "http", "json", "sql", "uml", "crud", "solid");
    private static final Pattern ENGLISH = Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + ENGLISH_TERMS.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .map(term -> Arrays.stream(term.split(" ")).map(Pattern::quote).collect(java.util.stream.Collectors.joining("\\h+")))
            .collect(java.util.stream.Collectors.joining("|")) + ")(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private AzureSpeechSsml() { }

    public static String render(String text, String language, String voice) {
        if (text == null || text.isBlank() || text.length() > 11000 || !("vi".equals(language) || "en".equals(language))
                || voice == null || !voice.matches("[a-z]{2}-[A-Z]{2}-[A-Za-z0-9]+Neural") || text.codePoints().anyMatch(cp -> !xmlCharacter(cp)))
            throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
        try {
            var output = new StringWriter(); var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            xml.writeStartElement("speak"); xml.writeDefaultNamespace(NS); xml.writeAttribute("version", "1.0"); locale(xml, language);
            xml.writeStartElement("voice"); xml.writeAttribute("name", voice);
            if (!AzureSpeechTtsProperties.multilingual(voice)) {
                // Giữ tương thích khi môi trường chủ động chọn HoaiMy/Aria cũ: chúng không hỗ trợ lang.
                xml.writeCharacters(text);
            } else if (language.equals("en")) span(xml, "en", text);
            else {
                var matcher = ENGLISH.matcher(text); int cursor = 0;
                while (matcher.find()) {
                    span(xml, "vi", text.substring(cursor, matcher.start()));
                    span(xml, "en", matcher.group()); cursor = matcher.end();
                }
                span(xml, "vi", text.substring(cursor));
            }
            xml.writeEndElement(); xml.writeEndElement(); xml.close();
            String ssml = output.toString();
            if (ssml.getBytes(StandardCharsets.UTF_8).length > MAX_SSML_BYTES) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
            return ssml;
        } catch (AppException safe) { throw safe; }
        catch (XMLStreamException invalid) { throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); }
    }
    private static void span(XMLStreamWriter xml, String language, String text) throws XMLStreamException {
        if (text.isEmpty()) return;
        xml.writeStartElement("lang"); locale(xml, language); xml.writeCharacters(text); xml.writeEndElement();
    }
    private static void locale(XMLStreamWriter xml, String language) throws XMLStreamException {
        xml.writeAttribute("xml", XML_NS, "lang", language.equals("vi") ? "vi-VN" : "en-US");
    }
    private static boolean xmlCharacter(int cp) {
        return cp == 9 || cp == 10 || cp == 13 || cp >= 0x20 && cp <= 0xD7FF || cp >= 0xE000 && cp <= 0xFFFD || cp >= 0x10000 && cp <= 0x10FFFF;
    }
}
