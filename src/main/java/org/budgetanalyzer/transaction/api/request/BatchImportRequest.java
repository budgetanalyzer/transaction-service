package org.budgetanalyzer.transaction.api.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request payload for batch importing transactions.
 *
 * <p>Accepts an ordered list of reviewed source files from the preview endpoint and persists every
 * accepted transaction atomically. API Bean Validation owns request shape; the service owns
 * business rules after every preview token is verified.
 */
@Schema(description = "Request for batch importing transactions")
public record BatchImportRequest(
    @Schema(description = "Ordered source files and reviewed transactions to import")
        @NotEmpty(message = "files list cannot be empty")
        @Valid
        List<BatchImportFileRequest> files) {}
