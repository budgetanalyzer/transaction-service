package org.budgetanalyzer.transaction.service.dto;

/**
 * Service-layer patch for updating a statement format.
 *
 * <p>All fields are optional - only non-null fields will be updated.
 *
 * @param displayName user-friendly display name
 * @param bankName bank name for transactions created from this format
 * @param defaultCurrencyIsoCode default currency ISO code
 * @param dateHeader CSV column header for date field
 * @param dateFormat date format pattern
 * @param descriptionHeader CSV column header for description field
 * @param creditHeader CSV column header for credit amount
 * @param debitHeader CSV column header for debit amount
 * @param typeHeader CSV column header for explicit transaction type
 * @param categoryHeader CSV column header for category
 * @param enabled whether this format is enabled for use
 */
public record StatementFormatPatch(
    String displayName,
    String bankName,
    String defaultCurrencyIsoCode,
    String dateHeader,
    String dateFormat,
    String descriptionHeader,
    String creditHeader,
    String debitHeader,
    String typeHeader,
    String categoryHeader,
    Boolean enabled) {}
