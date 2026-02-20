package org.budgetanalyzer.transaction.api.request;

import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating an existing statement format.
 *
 * <p>All fields are optional - only non-null fields will be updated. Format key and format type
 * cannot be changed after creation.
 *
 * @param displayName user-friendly display name for UI dropdowns
 * @param bankName bank name for transactions created from this format
 * @param defaultCurrencyIsoCode default currency ISO code for transactions
 * @param dateHeader CSV column header for date field
 * @param dateFormat date format pattern (e.g., "MM/dd/uu", "d MMM uuuu")
 * @param descriptionHeader CSV column header for description field
 * @param creditHeader CSV column header for credit amount
 * @param debitHeader CSV column header for debit amount
 * @param typeHeader CSV column header for explicit transaction type
 * @param categoryHeader CSV column header for category
 * @param enabled whether this format is enabled for use
 */
public record UpdateStatementFormatRequest(
    @Size(max = 100, message = "Display name must be at most 100 characters") String displayName,
    @Size(max = 100, message = "Bank name must be at most 100 characters") String bankName,
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
        String categoryHeader,
    Boolean enabled) {}
