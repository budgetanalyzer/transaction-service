package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.BatchImportFileResult;

/** Batch import result for one verified source file. */
@Schema(description = "Batch import result for one verified source file")
public record BatchImportFileResponse(
    @Schema(
            description = "Original filename verified from the preview token",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "statement-january.csv")
        String sourceFile,
    @Schema(
            description = "Number of transactions created from this source file",
            requiredMode = Schema.RequiredMode.REQUIRED)
        int created,
    @Schema(
            description = "Number of duplicate rows skipped from this source file",
            requiredMode = Schema.RequiredMode.REQUIRED)
        int duplicatesSkipped,
    @Schema(
            description = "Number of duplicate rows intentionally imported from this source file",
            requiredMode = Schema.RequiredMode.REQUIRED)
        int duplicatesImported,
    @Schema(
            description = "Transactions created from this source file",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<TransactionResponse> transactions) {

  /** Creates a per-file API response from a service-layer result. */
  public static BatchImportFileResponse from(BatchImportFileResult batchImportFileResult) {
    return new BatchImportFileResponse(
        batchImportFileResult.sourceFile(),
        batchImportFileResult.createdTransactions().size(),
        batchImportFileResult.duplicatesSkipped(),
        batchImportFileResult.duplicatesImported(),
        batchImportFileResult.createdTransactions().stream()
            .map(TransactionResponse::from)
            .toList());
  }
}
