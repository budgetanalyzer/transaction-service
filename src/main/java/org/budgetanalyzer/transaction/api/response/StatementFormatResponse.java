package org.budgetanalyzer.transaction.api.response;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

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
 * @param createdAt timestamp when the format was created
 * @param updatedAt timestamp when the format was last updated
 * @param createdBy user ID that created the format
 * @param updatedBy user ID that last updated the format
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
    boolean enabled,
    @Schema(
            description = "Timestamp when the statement format was created",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-04-08T10:30:00Z")
        Instant createdAt,
    @Schema(
            description = "Timestamp when the statement format was last updated",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "2026-04-08T10:45:00Z")
        Instant updatedAt,
    @Schema(
            description = "User ID that created this statement format",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "usr_admin123")
        String createdBy,
    @Schema(
            description = "User ID that last updated this statement format",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "usr_admin456")
        String updatedBy) {

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
        format.isEnabled(),
        format.getCreatedAt(),
        format.getUpdatedAt(),
        format.getCreatedBy(),
        format.getUpdatedBy());
  }
}
