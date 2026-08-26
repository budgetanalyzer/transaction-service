package org.budgetanalyzer.transaction.api.response;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.SavedViewSummary;

/** Metadata response for a static saved view. */
@Schema(description = "Static saved-view metadata")
public record SavedViewResponse(
    @Schema(description = "Unique view identifier", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
    @Schema(
            description = "User-facing view name",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SF Trip December 2024")
        String name,
    @Schema(
            description = "Number of active transaction memberships",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "25")
        long transactionCount,
    @Schema(description = "Creation timestamp", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,
    @Schema(
            description = "Last explicit update timestamp",
            requiredMode = Schema.RequiredMode.REQUIRED)
        Instant updatedAt) {

  /** Creates API metadata from a service-layer summary. */
  public static SavedViewResponse from(SavedViewSummary savedViewSummary) {
    var savedView = savedViewSummary.savedView();
    return new SavedViewResponse(
        savedView.getId(),
        savedView.getName(),
        savedViewSummary.transactionCount(),
        savedView.getCreatedAt(),
        savedView.getUpdatedAt());
  }
}
