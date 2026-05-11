package org.budgetanalyzer.transaction.api.response;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.PreviousFileImport;

/** Metadata for a previous matching file import. */
@Schema(description = "Metadata for a previous matching file import")
public record PreviousFileImportResponse(
    @Schema(
            description = "Original filename from the previous import",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "statement.csv")
        String originalFilename,
    @Schema(
            description = "Timestamp when the previous import completed",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-05-01T12:34:56Z")
        Instant importedAt,
    @Schema(
            description = "Format key used for the previous import",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "capital-one")
        String format,
    @Schema(
            description = "Account ID used for the previous import",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            example = "checking-12345")
        String accountId,
    @Schema(
            description = "Number of transactions recorded for the previous import",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "42")
        Integer transactionCount) {

  /** Creates a previous import response from a service-layer DTO. */
  public static PreviousFileImportResponse from(PreviousFileImport previousFileImport) {
    return new PreviousFileImportResponse(
        previousFileImport.originalFilename(),
        previousFileImport.importedAt(),
        previousFileImport.format(),
        previousFileImport.accountId(),
        previousFileImport.transactionCount());
  }
}
