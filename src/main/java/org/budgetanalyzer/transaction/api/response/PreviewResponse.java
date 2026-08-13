package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.PreviewResult;

/**
 * Response from the preview endpoint containing ordered per-file results before import.
 *
 * <p>The user can review and edit these transactions in the UI before submitting them for batch
 * import.
 */
@Schema(description = "Grouped response from an ordered multi-file transaction preview")
public record PreviewResponse(
    @Schema(
            description = "Ordered preview result for every uploaded source file",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<PreviewFileResponse> files) {

  /** Creates a PreviewResponse from a service-layer PreviewResult. */
  public static PreviewResponse from(PreviewResult previewResult) {
    return new PreviewResponse(
        previewResult.files().stream().map(PreviewFileResponse::from).toList());
  }
}
