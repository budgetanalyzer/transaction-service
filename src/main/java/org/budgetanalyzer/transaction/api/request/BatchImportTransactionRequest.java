package org.budgetanalyzer.transaction.api.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

/**
 * Transaction payload accepted by the batch import endpoint.
 *
 * <p>The field shape intentionally matches the preview response item so users can review and edit
 * transactions before posting them back for import.
 */
@Schema(description = "Previewed transaction data before import")
public record BatchImportTransactionRequest(
    @Schema(description = "Date of the transaction", example = "2024-04-12")
        @NotNull(message = "date is required")
        LocalDate date,
    @Schema(description = "Description of the transaction", example = "TAQUERIA DEL SOL #3")
        @NotBlank(message = "description is required")
        String description,
    @Schema(description = "Amount of the transaction", example = "55.12")
        @NotNull(message = "amount is required")
        BigDecimal amount,
    @Schema(
            description = "Type of the transaction",
            allowableValues = {"CREDIT", "DEBIT"},
            example = "DEBIT")
        @NotNull(message = "type is required")
        TransactionType type,
    @Schema(description = "Category extracted from source data", example = "Dining")
        String category,
    @Schema(description = "Name of the bank", example = "Capital One")
        @NotBlank(message = "bankName is required")
        String bankName,
    @Schema(description = "ISO currency code", example = "USD")
        @NotBlank(message = "currencyIsoCode is required")
        String currencyIsoCode,
    @Schema(description = "Account identifier (may be null)", example = "checking-12345")
        String accountId) {

  /** Converts this request payload to the service-layer preview DTO. */
  public PreviewTransaction toServiceDto() {
    return new PreviewTransaction(
        date, description, amount, type, category, bankName, currencyIsoCode, accountId);
  }
}
