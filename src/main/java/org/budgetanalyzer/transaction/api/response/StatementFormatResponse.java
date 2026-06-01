package org.budgetanalyzer.transaction.api.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.StatementFormatScope;
import org.budgetanalyzer.transaction.service.dto.StatementFormatListItem;

/**
 * Response DTO for statement format details.
 *
 * @param id unique identifier
 * @param displayName user-friendly display name for UI dropdowns
 * @param formatType type of format (CSV, PDF, XLSX)
 * @param bankName bank name for transactions created from this format
 * @param defaultCurrencyIsoCode default currency ISO code for transactions
 * @param scope visibility scope
 * @param ownerId owner ID for user-scoped formats
 * @param enabled whether this format is enabled for use
 * @param hidden whether the current user has hidden this format from normal selection
 * @param createdAt timestamp when the format was created
 * @param updatedAt timestamp when the format was last updated
 * @param createdBy user ID that created the format
 * @param updatedBy user ID that last updated the format
 */
public record StatementFormatResponse(
    Long id,
    String displayName,
    FormatType formatType,
    String bankName,
    String defaultCurrencyIsoCode,
    StatementFormatScope scope,
    String ownerId,
    boolean enabled,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(
            description =
                "Whether the current user has hidden this format from normal import selection",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "false")
        Boolean hidden,
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
        format.getDisplayName(),
        format.getFormatType(),
        format.getBankName(),
        format.getDefaultCurrencyIsoCode(),
        format.getScope(),
        format.getOwnerId(),
        format.isEnabled(),
        null,
        format.getCreatedAt(),
        format.getUpdatedAt(),
        format.getCreatedBy(),
        format.getUpdatedBy());
  }

  /**
   * Creates a response DTO from a list item.
   *
   * @param statementFormatListItem the service-layer list item to convert
   * @return the response DTO
   */
  public static StatementFormatResponse from(StatementFormatListItem statementFormatListItem) {
    var format = statementFormatListItem.statementFormat();
    return new StatementFormatResponse(
        format.getId(),
        format.getDisplayName(),
        format.getFormatType(),
        format.getBankName(),
        format.getDefaultCurrencyIsoCode(),
        format.getScope(),
        format.getOwnerId(),
        format.isEnabled(),
        statementFormatListItem.hidden(),
        format.getCreatedAt(),
        format.getUpdatedAt(),
        format.getCreatedBy(),
        format.getUpdatedBy());
  }
}
