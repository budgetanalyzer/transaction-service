package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.BatchImportResult;

/**
 * Response from batch import containing created transactions and duplicate information.
 *
 * <p>The response includes aggregate counts plus ordered results for every submitted source file.
 */
@Schema(description = "Response from batch transaction import")
public record BatchImportResponse(
    @Schema(
            description = "Number of transactions created",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "156")
        int created,
    @Schema(
            description =
                "Number of duplicate rows skipped because allowDuplicate was false or omitted",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "3")
        int duplicatesSkipped,
    @Schema(
            description = "Number of duplicate rows intentionally imported with allowDuplicate",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        int duplicatesImported,
    @Schema(
            description = "Ordered import result for every submitted source file",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<BatchImportFileResponse> files) {

  /** Creates a grouped API response from the service-layer result. */
  public static BatchImportResponse from(BatchImportResult batchImportResult) {
    return new BatchImportResponse(
        batchImportResult.created(),
        batchImportResult.duplicatesSkipped(),
        batchImportResult.duplicatesImported(),
        batchImportResult.files().stream().map(BatchImportFileResponse::from).toList());
  }
}
