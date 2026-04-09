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
    @Schema(description = "Original filename of the uploaded file", example = "cap-one-2024.csv")
        String sourceFile,
    @Schema(
            description = "Detected format key used for parsing (informational)",
            example = "capital-one-ytd")
        String detectedFormat,
    @Schema(description = "List of extracted transactions ready for review")
        List<PreviewTransactionResponse> transactions,
    @Schema(
            description =
                "List of warnings for fields with potential issues. "
                    + "Empty for CSV extraction; populated for PDF extraction where OCR confidence "
                    + "may be low.")
        List<PreviewWarning> warnings) {

  /** Creates a PreviewResponse from a service-layer PreviewResult. */
  public static PreviewResponse from(PreviewResult previewResult) {
    return new PreviewResponse(
        previewResult.sourceFile(),
        previewResult.detectedFormat(),
        previewResult.transactions().stream().map(PreviewTransactionResponse::from).toList(),
        previewResult.warnings().stream().map(PreviewWarning::from).toList());
  }
}
