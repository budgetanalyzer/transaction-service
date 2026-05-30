package org.budgetanalyzer.transaction.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.CsvWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.CsvWizardColumnMapping;

/** Request DTO for a user-confirmed CSV wizard column mapping. */
@Schema(description = "User-confirmed CSV column mapping for statement format creation")
public record CsvWizardColumnMappingRequest(
    @Schema(description = "CSV column containing transaction dates", example = "Date")
        @Size(max = 100, message = "Date column must be at most 100 characters")
        String dateColumn,
    @Schema(description = "Java DateTimeFormatter pattern for dateColumn", example = "MM/dd/uu")
        @Size(max = 50, message = "Date format must be at most 50 characters")
        String dateFormat,
    @Schema(description = "CSV column containing transaction descriptions", example = "Memo")
        @Size(max = 100, message = "Description column must be at most 100 characters")
        String descriptionColumn,
    @Schema(description = "Amount mapping mode", example = "SINGLE_AMOUNT_WITH_TYPE")
        @NotNull(message = "Amount mode is required")
        CsvWizardAmountMode amountMode,
    @Schema(description = "Amount column for SINGLE_AMOUNT_WITH_TYPE", example = "Amount")
        @Size(max = 100, message = "Amount column must be at most 100 characters")
        String amountColumn,
    @Schema(description = "Debit amount column for DEBIT_CREDIT_COLUMNS", example = "Debit")
        @Size(max = 100, message = "Debit column must be at most 100 characters")
        String debitColumn,
    @Schema(description = "Credit amount column for DEBIT_CREDIT_COLUMNS", example = "Credit")
        @Size(max = 100, message = "Credit column must be at most 100 characters")
        String creditColumn,
    @Schema(description = "Credit/debit type column for SINGLE_AMOUNT_WITH_TYPE", example = "Type")
        @Size(max = 100, message = "Type column must be at most 100 characters")
        String typeColumn,
    @Schema(description = "Optional category column", example = "Category")
        @Size(max = 100, message = "Category column must be at most 100 characters")
        String categoryColumn) {

  /** Converts this API request to its service-layer DTO. */
  public CsvWizardColumnMapping toServiceDto() {
    return new CsvWizardColumnMapping(
        dateColumn,
        dateFormat,
        descriptionColumn,
        amountMode,
        amountColumn,
        debitColumn,
        creditColumn,
        typeColumn,
        categoryColumn);
  }
}
