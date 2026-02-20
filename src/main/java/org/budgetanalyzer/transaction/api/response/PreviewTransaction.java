package org.budgetanalyzer.transaction.api.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.domain.TransactionType;

/**
 * Previewed transaction data before import.
 *
 * <p>Unlike TransactionResponse, this record does not have an ID (not yet persisted), nor createdAt
 * or updatedAt timestamps. It includes category extracted from source data.
 *
 * <p>This record is used both as output from the preview endpoint and as input to the batch import
 * endpoint, so it includes validation annotations for required fields.
 */
@Schema(description = "Previewed transaction data before import")
public record PreviewTransaction(
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
        String accountId) {}
