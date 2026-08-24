package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response containing the complete static membership of a saved view. */
@Schema(description = "Complete static saved-view transaction membership")
public record ViewMembershipResponse(
    @Schema(
            description = "Deterministically ordered transaction IDs",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "[123, 456, 789]")
        List<Long> transactionIds) {}
