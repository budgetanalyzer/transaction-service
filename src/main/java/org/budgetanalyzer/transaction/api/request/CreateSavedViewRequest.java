package org.budgetanalyzer.transaction.api.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request for creating a new saved view. */
@Schema(description = "Request to create a new saved view")
public record CreateSavedViewRequest(
    @Schema(
            description = "Name of the saved view",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SF Trip December 2024")
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        String name,
    @Schema(
            description = "Unordered transaction IDs to include in the static view",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "[123, 456]")
        @NotNull(message = "Transaction IDs are required")
        List<@NotNull(message = "Transaction ID is required") @Positive Long> transactionIds) {}
