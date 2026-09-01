package org.budgetanalyzer.transaction.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request for cloning a saved view under a new name. */
@Schema(description = "Request to clone a saved view")
public record CloneSavedViewRequest(
    @Schema(
            description = "Name for the cloned view",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "Copy of December review",
            maxLength = 255)
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        String name) {

  /** Trims surrounding whitespace from the submitted display name. */
  public CloneSavedViewRequest {
    if (name != null) {
      name = name.trim();
    }
  }
}
