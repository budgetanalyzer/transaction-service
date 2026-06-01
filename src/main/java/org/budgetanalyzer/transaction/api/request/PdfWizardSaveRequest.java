package org.budgetanalyzer.transaction.api.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.PdfTextTableYearSource;
import org.budgetanalyzer.transaction.service.dto.PdfWizardSaveCommand;

/** Request DTO for saving a PDF wizard statement format. */
@Schema(description = "PDF wizard save request")
public record PdfWizardSaveRequest(
    @Schema(description = "Display name for the saved statement format", example = "Example PDF")
        @NotBlank(message = "Display name is required")
        @Size(max = 100, message = "Display name must be at most 100 characters")
        String displayName,
    @Schema(
            description = "Bank name for transactions parsed by this format",
            example = "Example Bank")
        @NotBlank(message = "Bank name is required")
        @Size(max = 100, message = "Bank name must be at most 100 characters")
        String bankName,
    @Schema(description = "Default ISO 4217 currency code", example = "USD")
        @NotBlank(message = "Default currency ISO code is required")
        @Size(min = 3, max = 3, message = "Currency ISO code must be exactly 3 characters")
        String defaultCurrencyIsoCode,
    @Schema(description = "Header tokens that identify the confirmed transaction table")
        @Size(max = 10, message = "At most 10 table header tokens are supported")
        List<@Size(max = 100, message = "Header token must be at most 100 characters") String>
            headerMustContain,
    @Schema(
            description = "Minimum transaction rows required for the parser to match",
            example = "1")
        Integer minimumRows,
    @Schema(description = "How yearless date values should be completed", example = "EXPLICIT_DATE")
        @NotNull(message = "Year source is required")
        PdfTextTableYearSource yearSource,
    @Schema(description = "Confirmed PDF column mapping") @NotNull @Valid
        PdfWizardColumnMappingRequest mapping) {

  /** Converts this API request to its service-layer command. */
  public PdfWizardSaveCommand toServiceDto() {
    return new PdfWizardSaveCommand(
        displayName,
        bankName,
        defaultCurrencyIsoCode,
        headerMustContain,
        minimumRows,
        yearSource,
        mapping.toServiceDto());
  }
}
