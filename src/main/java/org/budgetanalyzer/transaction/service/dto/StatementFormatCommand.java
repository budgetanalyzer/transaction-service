package org.budgetanalyzer.transaction.service.dto;

import org.budgetanalyzer.transaction.domain.FormatType;

/**
 * Service-layer command to create a new statement format.
 *
 * @param formatKey unique format identifier
 * @param displayName user-friendly display name
 * @param formatType type of format (CSV, PDF, XLSX)
 * @param bankName bank name for transactions created from this format
 * @param defaultCurrencyIsoCode default currency ISO code
 * @param dateHeader CSV column header for date field (optional for non-CSV)
 * @param dateFormat date format pattern (optional for non-CSV)
 * @param descriptionHeader CSV column header for description field (optional for non-CSV)
 * @param creditHeader CSV column header for credit amount (optional for non-CSV)
 * @param debitHeader CSV column header for debit amount (optional)
 * @param typeHeader CSV column header for explicit transaction type (optional)
 * @param categoryHeader CSV column header for category (optional)
 */
public record StatementFormatCommand(
    String formatKey,
    String displayName,
    FormatType formatType,
    String bankName,
    String defaultCurrencyIsoCode,
    String dateHeader,
    String dateFormat,
    String descriptionHeader,
    String creditHeader,
    String debitHeader,
    String typeHeader,
    String categoryHeader) {}
