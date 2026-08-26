package org.budgetanalyzer.transaction.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request for renaming a saved view. */
@Schema(description = "Request to rename a saved view")
public record UpdateSavedViewRequest(
    @Schema(
            description = "New name for the view",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SF Trip December 2024",
            maxLength = 255)
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        String name) {}
