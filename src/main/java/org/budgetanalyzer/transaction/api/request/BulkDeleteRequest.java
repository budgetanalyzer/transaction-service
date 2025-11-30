package org.budgetanalyzer.transaction.api.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request object for bulk deleting transactions.
 *
 * <p>Allows deleting multiple transactions in a single operation. All transactions will be
 * soft-deleted (marked as deleted but retained in the database).
 */
@Schema(description = "Request to bulk delete multiple transactions")
public record BulkDeleteRequest(
    @Schema(description = "List of transaction IDs to delete", example = "[1, 2, 3]")
        @NotNull(message = "Transaction IDs list cannot be null")
        @NotEmpty(message = "Transaction IDs list cannot be empty")
        List<Long> ids) {}
