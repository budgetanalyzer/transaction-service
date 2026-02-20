package org.budgetanalyzer.transaction.api.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.domain.MembershipType;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;

/** Response for a transaction within a saved view, including membership information. */
@Schema(description = "Transaction response with view membership information")
public record ViewTransactionResponse(
    @Schema(
            description = "Unique identifier for the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
    @Schema(description = "Identifier for the account associated with the transaction")
        String accountId,
    @Schema(
            description = "Name of the bank where the transaction occurred",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String bankName,
    @Schema(description = "Date of the transaction", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate date,
    @Schema(
            description = "ISO currency code for the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String currencyIsoCode,
    @Schema(description = "Amount of the transaction", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal amount,
    @Schema(description = "Type of the transaction", requiredMode = Schema.RequiredMode.REQUIRED)
        TransactionType type,
    @Schema(
            description = "Description of the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String description,
    @Schema(
            description = "Timestamp when the transaction was created",
            requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,
    @Schema(
            description = "Timestamp when the transaction was last updated",
            requiredMode = Schema.RequiredMode.REQUIRED)
        Instant updatedAt,
    @Schema(
            description = "How this transaction is included in the view (MATCHED or PINNED)",
            requiredMode = Schema.RequiredMode.REQUIRED)
        MembershipType membershipType) {

  /** Creates a response from a Transaction entity with a membership type. */
  public static ViewTransactionResponse from(
      Transaction transaction, MembershipType membershipType) {
    return new ViewTransactionResponse(
        transaction.getId(),
        transaction.getAccountId(),
        transaction.getBankName(),
        transaction.getDate(),
        transaction.getCurrencyIsoCode(),
        transaction.getAmount(),
        transaction.getType(),
        transaction.getDescription(),
        transaction.getCreatedAt(),
        transaction.getUpdatedAt(),
        membershipType);
  }
}
