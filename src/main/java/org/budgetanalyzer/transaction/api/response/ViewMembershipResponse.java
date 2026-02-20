package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.ViewMembership;

/**
 * API response containing transaction IDs grouped by membership type in a saved view.
 *
 * <p>Only includes active (non-deleted) transactions. Soft-deleted transactions are excluded.
 */
@Schema(
    description =
        "Transaction IDs grouped by membership type in a saved view. "
            + "Only includes active (non-deleted) transactions.")
public record ViewMembershipResponse(
    @Schema(
            description =
                "IDs of active transactions matching view criteria (excluding excluded IDs)",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "[123, 456, 789]")
        List<Long> matched,
    @Schema(
            description =
                "IDs of active transactions explicitly pinned (excluding those already matched)",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "[101, 102]")
        List<Long> pinned,
    @Schema(
            description = "IDs of active transactions explicitly excluded from matched set",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "[234]")
        List<Long> excluded) {

  /** Creates a response from a ViewMembership service DTO. */
  public static ViewMembershipResponse from(ViewMembership membership) {
    return new ViewMembershipResponse(
        membership.matched(), membership.pinned(), membership.excluded());
  }
}
