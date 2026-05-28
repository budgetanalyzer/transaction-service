package org.budgetanalyzer.transaction.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.CsvWizardSaveCommand;

/** Request DTO for saving a CSV wizard statement format. */
@Schema(description = "CSV wizard save request")
public record CsvWizardSaveRequest(
    @Schema(description = "Display name for the saved statement format", example = "Example CSV")
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
    @Schema(description = "Confirmed CSV column mapping") @NotNull @Valid
        CsvWizardColumnMappingRequest mapping) {

  /** Converts this API request to its service-layer command. */
  public CsvWizardSaveCommand toServiceDto() {
    return new CsvWizardSaveCommand(
        displayName, bankName, defaultCurrencyIsoCode, mapping.toServiceDto());
  }
}
