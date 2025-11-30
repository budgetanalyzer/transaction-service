package org.budgetanalyzer.transaction.api.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.domain.TransactionType;

/** Filter object for querying transactions based on one or more criteria. */
@Schema(description = "Filter for querying transactions by various fields")
public record TransactionFilter(
    @Schema(description = "Unique identifier for the transaction", example = "1") Long id,
    @Schema(
            description = "Identifier for the account associated with the transaction",
            example = "checking-3223")
        String accountId,
    @Schema(
            description = "Name of the bank where the transaction occurred",
            example = "USD Bank")
        String bankName,
    @Schema(description = "Start date for transaction date range", example = "2025-10-01")
        LocalDate dateFrom,
    @Schema(description = "End date for transaction date range", example = "2025-10-14")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate dateTo,
    @Schema(description = "ISO currency code for the transaction", example = "USD")
        String currencyIsoCode,
    @Schema(description = "Minimum transaction amount", example = "10.00") BigDecimal minAmount,
    @Schema(description = "Maximum transaction amount", example = "500.00") BigDecimal maxAmount,
    @Schema(
            description = "Type of the transaction",
            allowableValues = {"CREDIT", "DEBIT"},
            example = "DEBIT")
        TransactionType type,
    @Schema(description = "Text to match in the transaction description", example = "Grocery")
        String description,
    @Schema(description = "Start of creation timestamp range", example = "2025-10-14T00:00:00Z")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        Instant createdAfter,
    @Schema(description = "End of creation timestamp range", example = "2025-10-15T00:00:00Z")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        Instant createdBefore,
    @Schema(description = "Start of last update timestamp range", example = "2025-10-14T00:00:00Z")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        Instant updatedAfter,
    @Schema(description = "End of last update timestamp range", example = "2025-10-15T00:00:00Z")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        Instant updatedBefore) {}
