package org.budgetanalyzer.transaction.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.api.ViewCriteriaApi;

/** Request for updating a saved view. */
@Schema(description = "Request to update a saved view")
public record UpdateSavedViewRequest(
    @Schema(description = "New name for the view", example = "SF Trip December 2024")
        @Size(max = 255, message = "Name must be at most 255 characters")
        String name,
    @Schema(description = "New filter criteria for the view") @Valid ViewCriteriaApi criteria,
    @Schema(description = "If true, the view includes transactions up to current date")
        Boolean openEnded) {}
