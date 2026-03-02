package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

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
        List<PreviewTransaction> transactions,
    @Schema(
            description =
                "List of warnings for fields with potential issues. "
                    + "Empty for CSV extraction; populated for PDF extraction where OCR confidence "
                    + "may be low.")
        List<PreviewWarning> warnings) {}
