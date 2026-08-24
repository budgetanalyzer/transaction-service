package org.budgetanalyzer.transaction.api.request;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request for atomically applying a static saved-view membership delta. */
@Schema(description = "Static saved-view membership additions and removals")
public record UpdateSavedViewTransactionsRequest(
    @Schema(
            description = "Unordered transaction IDs to add",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "[123, 456]")
        @NotNull(message = "Add transaction IDs are required")
        List<@NotNull(message = "Transaction ID is required") @Positive Long> addTransactionIds,
    @Schema(
            description = "Unordered transaction IDs to remove",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "[789]")
        @NotNull(message = "Remove transaction IDs are required")
        List<@NotNull(message = "Transaction ID is required") @Positive Long>
            removeTransactionIds) {}
