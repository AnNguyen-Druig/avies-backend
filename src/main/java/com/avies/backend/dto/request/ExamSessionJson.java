package com.avies.backend.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Đọc JSON nghiêm ngặt riêng cho phiên thi, tránh cắt số lẻ hoặc suy diễn timestamp thiếu offset. */
public final class ExamSessionJson {
    /** Lớp tiện ích không được khởi tạo. */
    private ExamSessionJson() { }

    /** Chỉ nhận token JSON số nguyên trong miền Integer. */
    public static class IntegerValue extends ValueDeserializer<Integer> {
        /** Không ép số lẻ hoặc chuỗi thành số câu/phút thi. */
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Integer.class, "Giá trị phải là số nguyên JSON.");
            return parser.getIntValue();
        }
    }

    /** Chỉ nhận ID dưới dạng số nguyên Long. */
    public static class Identifier extends ValueDeserializer<Long> {
        /** Từ chối ID lẻ, chuỗi hoặc số vượt miền Long. */
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Long.class, "ID phải là số nguyên JSON.");
            return parser.getLongValue();
        }
    }

    /** Chỉ nhận timestamp ISO-8601 dạng chuỗi có offset hoặc Z. */
    public static class Timestamp extends ValueDeserializer<OffsetDateTime> {
        /** Giữ chính xác thời điểm, không dùng timezone của máy để bổ sung offset còn thiếu. */
        @Override
        public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING))
                return context.reportInputMismatch(OffsetDateTime.class, "Thời gian phải là chuỗi ISO-8601 có offset.");
            try {
                return OffsetDateTime.parse(parser.getString());
            } catch (DateTimeParseException exception) {
                return context.reportInputMismatch(OffsetDateTime.class, "Thời gian phải là chuỗi ISO-8601 có offset.");
            }
        }
    }

    /** Chỉ nhận tiêu đề dạng chuỗi JSON. */
    public static class Text extends ValueDeserializer<String> {
        /** Không ép số hoặc boolean thành tiêu đề. */
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING))
                return context.reportInputMismatch(String.class, "Tiêu đề phải là chuỗi JSON.");
            return parser.getString();
        }
    }
}
