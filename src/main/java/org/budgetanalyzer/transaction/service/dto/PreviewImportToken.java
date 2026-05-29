package org.budgetanalyzer.transaction.service.dto;

import java.time.Instant;

/** Verified preview import token metadata used to identify an uploaded source file. */
public record PreviewImportToken(
    String ownerId,
    String contentHash,
    String originalFilename,
    Long statementFormatId,
    Long parserRevisionId,
    String accountId,
    Long fileSizeBytes,
    Instant issuedAt,
    Instant expiresAt) {}
