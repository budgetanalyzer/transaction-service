package org.budgetanalyzer.transaction.api.response;

import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.StatementFormat;

/**
 * Response DTO for statement format details.
 *
 * @param id unique identifier
 * @param formatKey unique format identifier (e.g., "capital-one-bank-csv", "bkk-bank-csv")
 * @param displayName user-friendly display name for UI dropdowns
 * @param formatType type of format (CSV, PDF, XLSX)
 * @param bankName bank name for transactions created from this format
 * @param defaultCurrencyIsoCode default currency ISO code for transactions
 * @param dateHeader CSV column header for date field
 * @param dateFormat date format pattern
 * @param descriptionHeader CSV column header for description field
 * @param creditHeader CSV column header for credit amount
 * @param debitHeader CSV column header for debit amount
 * @param typeHeader CSV column header for explicit transaction type
 * @param categoryHeader CSV column header for category
 * @param enabled whether this format is enabled for use
 */
public record StatementFormatResponse(
    Long id,
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
    String categoryHeader,
    boolean enabled) {

  /**
   * Creates a response DTO from a StatementFormat entity.
   *
   * @param format the entity to convert
   * @return the response DTO
   */
  public static StatementFormatResponse from(StatementFormat format) {
    return new StatementFormatResponse(
        format.getId(),
        format.getFormatKey(),
        format.getDisplayName(),
        format.getFormatType(),
        format.getBankName(),
        format.getDefaultCurrencyIsoCode(),
        format.getDateHeader(),
        format.getDateFormat(),
        format.getDescriptionHeader(),
        format.getCreditHeader(),
        format.getDebitHeader(),
        format.getTypeHeader(),
        format.getCategoryHeader(),
        format.isEnabled());
  }
}
