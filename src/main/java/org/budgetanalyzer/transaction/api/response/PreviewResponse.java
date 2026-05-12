package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.PreviewResult;

/**
 * Response from the preview endpoint containing extracted transactions before import.
 *
 * <p>The user can review and edit these transactions in the UI before submitting them for batch
 * import.
 */
@Schema(description = "Response from transaction preview containing extracted transactions")
public record PreviewResponse(
    @Schema(
            description = "Original filename of the uploaded file",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "cap-one-2024.csv")
        String sourceFile,
    @Schema(
            description = "Detected format key used for parsing (informational)",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "capital-one-ytd")
        String detectedFormat,
    @Schema(
            description =
                "Opaque encrypted source-file token required for token-backed batch import",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "v2.dGVzdGl2MTIzNDU.Kc4WwTqfh1sFD8pxVq7Hxg")
        String previewImportToken,
    @Schema(
            description =
                "File-level import history status for the uploaded bytes and authenticated user",
            requiredMode = Schema.RequiredMode.REQUIRED)
        PreviewFileImportStatusResponse fileImport,
    @Schema(
            description =
                "List of extracted transactions ready for review, including advisory duplicate "
                    + "metadata for each row",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<PreviewTransactionResponse> transactions) {

  /** Creates a PreviewResponse from a service-layer PreviewResult. */
  public static PreviewResponse from(PreviewResult previewResult) {
    return new PreviewResponse(
        previewResult.sourceFile(),
        previewResult.detectedFormat(),
        previewResult.previewImportToken(),
        PreviewFileImportStatusResponse.from(previewResult.fileImport()),
        previewResult.transactions().stream().map(PreviewTransactionResponse::from).toList());
  }
}
