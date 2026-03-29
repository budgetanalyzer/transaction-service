package org.budgetanalyzer.transaction.api.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;

/**
 * Admin response for transaction data.
 *
 * <p>This record extends the standard transaction response with the {@code ownerId} field, allowing
 * admin users to see which user owns each transaction.
 */
@Schema(description = "Admin transaction response including owner information")
public record AdminTransactionResponse(
    @Schema(
            description = "Unique identifier for the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long id,
    @Schema(
            description = "ID of the user who owns this transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "usr_test123")
        String ownerId,
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
   * Creates an AdminTransactionResponse from a Transaction entity.
   *
   * @param transaction the transaction entity to convert
   * @return a new AdminTransactionResponse instance
   */
  public static AdminTransactionResponse from(Transaction transaction) {
    return new AdminTransactionResponse(
        transaction.getId(),
        transaction.getOwnerId(),
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
