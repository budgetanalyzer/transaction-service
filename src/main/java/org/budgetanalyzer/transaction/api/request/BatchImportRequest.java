package org.budgetanalyzer.transaction.api.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request payload for batch importing transactions.
 *
 * <p>Accepts a list of transaction DTOs (typically from the preview endpoint after user edits) and
 * persists them atomically.
 */
@Schema(description = "Request for batch importing transactions")
public record BatchImportRequest(
    @Schema(description = "List of transactions to import")
        @NotEmpty(message = "transactions list cannot be empty")
        @Valid
        List<BatchImportTransactionRequest> transactions) {}
