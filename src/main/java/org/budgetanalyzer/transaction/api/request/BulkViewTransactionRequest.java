package org.budgetanalyzer.transaction.api.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request object for bulk saved-view transaction updates. */
@Schema(description = "Request to bulk pin or exclude multiple transactions in a saved view")
public record BulkViewTransactionRequest(
    @Schema(description = "List of transaction IDs to update", example = "[1, 2, 3]")
        @NotNull(message = "Transaction IDs list cannot be null")
        @NotEmpty(message = "Transaction IDs list cannot be empty")
        List<Long> ids) {}
