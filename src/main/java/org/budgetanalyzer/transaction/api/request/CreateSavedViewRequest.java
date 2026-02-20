package org.budgetanalyzer.transaction.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.api.ViewCriteriaApi;

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
            description = "Filter criteria for the view",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Criteria is required")
        @Valid
        ViewCriteriaApi criteria,
    @Schema(
            description = "If true, the view includes transactions up to current date",
            example = "false")
        boolean openEnded) {}
