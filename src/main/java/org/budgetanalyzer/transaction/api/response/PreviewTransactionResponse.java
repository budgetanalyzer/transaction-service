package org.budgetanalyzer.transaction.api.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.PreviewDuplicateReason;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

/**
 * Previewed transaction item returned from the preview endpoint.
 *
 * <p>The field shape intentionally mirrors the batch import request item so previewed transactions
 * can round-trip through client-side edits before import.
 */
@Schema(description = "Previewed transaction data before import")
public record PreviewTransactionResponse(
    @Schema(
            description = "Date of the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2024-04-12")
        @NotNull(message = "date is required")
        LocalDate date,
    @Schema(
            description = "Description of the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "TAQUERIA DEL SOL #3")
        @NotBlank(message = "description is required")
        String description,
    @Schema(
            description = "Amount of the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "55.12")
        @NotNull(message = "amount is required")
        BigDecimal amount,
    @Schema(
            description = "Type of the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"CREDIT", "DEBIT"},
            example = "DEBIT")
        @NotNull(message = "type is required")
        TransactionType type,
    @Schema(
            description = "Category extracted from source data",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "Dining")
        String category,
    @Schema(
            description = "Name of the bank",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "Capital One")
        @NotBlank(message = "bankName is required")
        String bankName,
    @Schema(
            description = "ISO currency code",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "USD")
        @NotBlank(message = "currencyIsoCode is required")
        String currencyIsoCode,
    @Schema(
            description =
                "Account identifier. Null and empty values are equivalent for duplicate "
                    + "detection.",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "checking-12345")
        String accountId,
    @Schema(
            description =
                "Whether this row appears to duplicate an existing active owner-owned "
                    + "transaction or an earlier row in the same preview payload",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "false")
        boolean duplicate,
    @Schema(
            description =
                "Reason this row was marked as a duplicate. Null when duplicate is false.",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            allowableValues = {"EXISTING_TRANSACTION", "IN_BATCH"},
            nullable = true,
            example = "EXISTING_TRANSACTION")
        PreviewDuplicateReason duplicateReason) {

  /** Creates a preview response item from a service-layer preview DTO. */
  public static PreviewTransactionResponse from(PreviewTransaction serviceDto) {
    return new PreviewTransactionResponse(
        serviceDto.date(),
        serviceDto.description(),
        serviceDto.amount(),
        serviceDto.type(),
        serviceDto.category(),
        serviceDto.bankName(),
        serviceDto.currencyIsoCode(),
        serviceDto.accountId(),
        serviceDto.duplicate(),
        serviceDto.duplicateReason());
  }
}
