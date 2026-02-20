package org.budgetanalyzer.transaction.api.response;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.api.ViewCriteriaApi;
import org.budgetanalyzer.transaction.domain.SavedView;

/** Response for a saved view. */
@Schema(description = "Saved view response")
public record SavedViewResponse(
    @Schema(
            description = "Unique identifier for the view",
            requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
    @Schema(
            description = "Name of the view",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SF Trip December 2024")
        String name,
    @Schema(
            description = "Filter criteria for the view",
            requiredMode = Schema.RequiredMode.REQUIRED)
        ViewCriteriaApi criteria,
    @Schema(
            description = "If true, the view includes transactions up to the current date",
            requiredMode = Schema.RequiredMode.REQUIRED)
        boolean openEnded,
    @Schema(
            description = "Number of pinned transactions",
            requiredMode = Schema.RequiredMode.REQUIRED)
        int pinnedCount,
    @Schema(
            description = "Number of excluded transactions",
            requiredMode = Schema.RequiredMode.REQUIRED)
        int excludedCount,
    @Schema(
            description = "Total number of transactions matching this view",
            requiredMode = Schema.RequiredMode.REQUIRED)
        long transactionCount,
    @Schema(
            description = "Timestamp when the view was created",
            requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,
    @Schema(
            description = "Timestamp when the view was last updated",
            requiredMode = Schema.RequiredMode.REQUIRED)
        Instant updatedAt) {

  /** Creates a response from a SavedView entity with a transaction count. */
  public static SavedViewResponse from(SavedView view, long transactionCount) {
    return new SavedViewResponse(
        view.getId(),
        view.getName(),
        ViewCriteriaApi.from(view.getCriteria()),
        view.isOpenEnded(),
        view.getPinnedIds().size(),
        view.getExcludedIds().size(),
        transactionCount,
        view.getCreatedAt(),
        view.getUpdatedAt());
  }
}
