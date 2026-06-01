package org.budgetanalyzer.transaction.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.PdfWizardColumnMapping;

/** Request DTO for a user-confirmed PDF wizard column mapping. */
@Schema(description = "User-confirmed PDF table column mapping for statement format creation")
public record PdfWizardColumnMappingRequest(
    @Schema(description = "PDF table header containing transaction dates", example = "Date")
        @Size(max = 100, message = "Date header must be at most 100 characters")
        String dateHeader,
    @Schema(description = "Java DateTimeFormatter pattern for dateHeader", example = "MM/dd/uuuu")
        @Size(max = 50, message = "Date format must be at most 50 characters")
        String dateFormat,
    @Schema(description = "PDF table header containing descriptions", example = "Description")
        @Size(max = 100, message = "Description header must be at most 100 characters")
        String descriptionHeader,
    @Schema(description = "Amount mapping mode", example = "SIGNED_AMOUNT")
        @NotNull(message = "Amount mode is required")
        PdfWizardAmountMode amountMode,
    @Schema(description = "Signed amount header for SIGNED_AMOUNT", example = "Amount")
        @Size(max = 100, message = "Amount header must be at most 100 characters")
        String amountHeader,
    @Schema(description = "Debit amount header for DEBIT_CREDIT_COLUMNS", example = "Debit")
        @Size(max = 100, message = "Debit header must be at most 100 characters")
        String debitHeader,
    @Schema(description = "Credit amount header for DEBIT_CREDIT_COLUMNS", example = "Credit")
        @Size(max = 100, message = "Credit header must be at most 100 characters")
        String creditHeader,
    @Schema(description = "Optional credit/debit type header", example = "Type")
        @Size(max = 100, message = "Type header must be at most 100 characters")
        String typeHeader,
    @Schema(description = "Direction represented by negative signed amounts", example = "CREDIT")
        PdfTextTableNegativeMeans negativeMeans) {

  /** Converts this API request to its service-layer DTO. */
  public PdfWizardColumnMapping toServiceDto() {
    return new PdfWizardColumnMapping(
        dateHeader,
        dateFormat,
        descriptionHeader,
        amountMode,
        amountHeader,
        debitHeader,
        creditHeader,
        typeHeader,
        negativeMeans);
  }
}
