package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response object for bulk saved-view transaction updates. */
@Schema(description = "Response from bulk saved-view transaction update operation")
public record BulkViewTransactionResponse(
    @Schema(description = "Number of transaction IDs successfully updated", example = "5")
        int updatedCount,
    @Schema(
            description =
                "List of transaction IDs that were not found, not owned by the user, or deleted",
            example = "[999, 1000]")
        List<Long> notFoundIds) {}
