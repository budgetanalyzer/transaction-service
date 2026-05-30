package org.budgetanalyzer.transaction.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.CsvWizardMappingPreviewCommand;

/** Request DTO for a read-only CSV wizard parser preview. */
@Schema(description = "CSV wizard parser preview request")
public record CsvWizardMappingPreviewRequest(
    @Schema(description = "Bank name to place on preview rows", example = "Example Bank")
        @NotBlank(message = "Bank name is required")
        @Size(max = 100, message = "Bank name must be at most 100 characters")
        String bankName,
    @Schema(description = "Default ISO 4217 currency code", example = "USD")
        @NotBlank(message = "Default currency ISO code is required")
        @Size(min = 3, max = 3, message = "Currency ISO code must be exactly 3 characters")
        String defaultCurrencyIsoCode,
    @Schema(description = "Optional account ID to place on preview rows", example = "checking-001")
        @Size(max = 100, message = "Account ID must be at most 100 characters")
        String accountId,
    @Schema(description = "Confirmed CSV column mapping") @NotNull @Valid
        CsvWizardColumnMappingRequest mapping) {

  /** Converts this API request to its service-layer command. */
  public CsvWizardMappingPreviewCommand toServiceDto() {
    return new CsvWizardMappingPreviewCommand(
        bankName, defaultCurrencyIsoCode, accountId, mapping.toServiceDto());
  }
}
