package org.budgetanalyzer.transaction.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Warning for a specific field in a previewed transaction.
 *
 * <p>Schema is defined now for future use with PDF extraction where confidence may be low. For CSV
 * imports, the warnings list will be empty.
 */
@Schema(description = "Warning for a specific field in a previewed transaction")
public record PreviewWarning(
    @Schema(description = "Zero-based index of the transaction in the preview list", example = "12")
        int index,
    @Schema(description = "Name of the field with the warning", example = "amount") String field,
    @Schema(description = "Warning message", example = "OCR confidence low") String message) {}
