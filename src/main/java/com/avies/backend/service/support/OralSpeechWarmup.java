package com.avies.backend.service.support;

import java.time.OffsetDateTime;

/** Chỉ chuẩn bị audio cho turn vừa chốt; không gửi nội dung đề qua HTTP hoặc mở giờ trả lời. */
public record OralSpeechWarmup(OralSpeechPlan plan, OffsetDateTime deadline) { }
