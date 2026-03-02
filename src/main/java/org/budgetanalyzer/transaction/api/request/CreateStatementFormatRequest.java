package org.budgetanalyzer.transaction.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.budgetanalyzer.transaction.domain.FormatType;

/**
 * Request DTO for creating a new statement format.
 *
 * @param formatKey unique format identifier (e.g., "capital-one-bank-csv", "bkk-bank-csv")
 * @param displayName user-friendly display name for UI dropdowns
 * @param formatType type of format (CSV, PDF, XLSX)
 * @param bankName bank name for transactions created from this format
 * @param defaultCurrencyIsoCode default currency ISO code for transactions
 * @param dateHeader CSV column header for date field (required for CSV)
 * @param dateFormat date format pattern (e.g., "MM/dd/uu", "d MMM uuuu")
 * @param descriptionHeader CSV column header for description field (required for CSV)
 * @param creditHeader CSV column header for credit amount (required for CSV)
 * @param debitHeader CSV column header for debit amount (optional for CSV)
 * @param typeHeader CSV column header for explicit transaction type (optional)
 * @param categoryHeader CSV column header for category (optional)
 */
public record CreateStatementFormatRequest(
    @NotBlank(message = "Format key is required")
        @Size(max = 50, message = "Format key must be at most 50 characters")
        @Pattern(
            regexp = "^[a-z0-9-]+$",
            message = "Format key must contain only lowercase letters, numbers, and hyphens")
        String formatKey,
    @NotBlank(message = "Display name is required")
        @Size(max = 100, message = "Display name must be at most 100 characters")
        String displayName,
    @NotNull(message = "Format type is required") FormatType formatType,
    @NotBlank(message = "Bank name is required")
        @Size(max = 100, message = "Bank name must be at most 100 characters")
        String bankName,
    @NotBlank(message = "Default currency ISO code is required")
        @Size(min = 3, max = 3, message = "Currency ISO code must be exactly 3 characters")
        String defaultCurrencyIsoCode,
    @Size(max = 50, message = "Date header must be at most 50 characters") String dateHeader,
    @Size(max = 50, message = "Date format must be at most 50 characters") String dateFormat,
    @Size(max = 50, message = "Description header must be at most 50 characters")
        String descriptionHeader,
    @Size(max = 50, message = "Credit header must be at most 50 characters") String creditHeader,
    @Size(max = 50, message = "Debit header must be at most 50 characters") String debitHeader,
    @Size(max = 50, message = "Type header must be at most 50 characters") String typeHeader,
    @Size(max = 50, message = "Category header must be at most 50 characters")
        String categoryHeader) {}
