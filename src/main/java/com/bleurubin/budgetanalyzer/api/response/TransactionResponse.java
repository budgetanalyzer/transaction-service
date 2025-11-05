package com.bleurubin.budgetanalyzer.api.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.domain.TransactionType;

/**
 * Response DTO for transaction data.
 *
 * <p>This record represents the API response format for transaction information, excluding internal
 * soft-delete fields (deleted, deletedAt) which should not be exposed to API consumers.
 */
@Schema(description = "Transaction response representing a financial transaction")
public record TransactionResponse(
    @Schema(
            description = "Unique identifier for the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long id,
    @Schema(
            description = "Identifier for the account associated with the transaction",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "checking-3223")
        String accountId,
    @Schema(
            description = "Name of the bank where the transaction occurred",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "Capital One")
        String bankName,
    @Schema(
            description = "Date of the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2025-10-14")
        LocalDate date,
    @Schema(
            description = "ISO currency code for the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "USD")
        String currencyIsoCode,
    @Schema(
            description = "Amount of the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "100.50")
        BigDecimal amount,
    @Schema(
            description = "Type of the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"CREDIT", "DEBIT"},
            example = "DEBIT")
        TransactionType type,
    @Schema(
            description = "Description of the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "Grocery shopping")
        String description,
    @Schema(
            description = "Timestamp when the transaction was created",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2025-10-14T10:30:00Z")
        Instant createdAt,
    @Schema(
            description = "Timestamp when the transaction was last updated",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2025-10-14T10:30:00Z")
        Instant updatedAt) {

  /**
   * Creates a TransactionResponse from a Transaction entity.
   *
   * @param transaction The transaction entity to convert
   * @return A new TransactionResponse instance
   */
  public static TransactionResponse from(Transaction transaction) {
    // Note: createdAt and updatedAt are inherited from AuditableEntity base class
    return new TransactionResponse(
        transaction.getId(),
        transaction.getAccountId(),
        transaction.getBankName(),
        transaction.getDate(),
        transaction.getCurrencyIsoCode(),
        transaction.getAmount(),
        transaction.getType(),
        transaction.getDescription(),
        transaction.getCreatedAt(),
        transaction.getUpdatedAt());
  }
}
