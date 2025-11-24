package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response object for bulk delete operations.
 *
 * <p>Contains the count of successfully deleted transactions and lists any IDs that were not found
 * or could not be deleted.
 */
@Schema(description = "Response from bulk delete operation")
public record BulkDeleteResponse(
    @Schema(description = "Number of transactions successfully deleted", example = "5")
        int deletedCount,
    @Schema(
            description = "List of transaction IDs that were not found or already deleted",
            example = "[999, 1000]")
        List<Long> notFoundIds) {}
