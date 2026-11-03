package com.avies.backend.service.support;

/** Thông báo sau commit, không mang câu hỏi, transcript, token hoặc dữ liệu đánh giá. */
public record OralProgressChanged(long examId, long revision) { }
