package org.budgetanalyzer.transaction.api.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.domain.TransactionType;

/** Request to create one manual transaction for the authenticated owner. */
@Schema(description = "Manual transaction data")
public record CreateTransactionRequest(
    @Schema(
            description = "Date of the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-09-18")
        @NotNull(message = "date is required")
        LocalDate date,
    @Schema(
            description = "Description of the transaction",
            requiredMode = Schema.RequiredMode.REQUIRED,
            maxLength = 500,
            example = "Grocery shopping")
        @NotBlank(message = "description is required")
        @Size(max = 500, message = "Description cannot exceed 500 characters")
        String description,
    @Schema(
            description = "Positive transaction amount; direction is specified by type",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "100.50")
        @NotNull(message = "amount is required")
        @Positive(message = "amount must be positive")
        @Digits(
            integer = 36,
            fraction = 2,
            message = "amount must have at most 36 integer digits and 2 fractional digits")
        BigDecimal amount,
    @Schema(
            description = "Three-character ISO 4217 currency code",
            requiredMode = Schema.RequiredMode.REQUIRED,
            minLength = 3,
            maxLength = 3,
            example = "USD")
        @NotBlank(message = "currencyIsoCode is required")
        @Size(min = 3, max = 3, message = "currencyIsoCode must contain exactly 3 characters")
        String currencyIsoCode,
    @Schema(
            description = "Direction of the positive transaction amount",
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"CREDIT", "DEBIT"},
            example = "DEBIT")
        @NotNull(message = "type is required")
        TransactionType type,
    @Schema(
            description = "Optional bank name; omitted from responses when no bank was recorded",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            maxLength = 255,
            example = "Capital One")
        @Size(max = 255, message = "Bank name cannot exceed 255 characters")
        String bankName,
    @Schema(
            description = "Optional freehand account identifier",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            maxLength = 100,
            example = "checking-12345")
        @Size(max = 100, message = "Account ID cannot exceed 100 characters")
        String accountId) {}
