package com.avies.backend.service.support;

import java.util.UUID;

/** Claim nội bộ, không serialize đường dẫn/hash/nonce ra API Student. */
public record OralTranscriptionWork(long examId, UUID submissionId, UUID claimId, String language, String sha256) { }
