package org.budgetanalyzer.transaction.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.PreviewFileImportStatus;

/** File import history status returned from the preview endpoint. */
@Schema(description = "File import history status for the previewed upload")
public record PreviewFileImportStatusResponse(
    @Schema(
            description = "Whether the current user has already imported the exact same file bytes",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "false")
        boolean alreadyImported,
    @Schema(
            description = "Stable warning code for file-level preview status",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            example = "FILE_ALREADY_IMPORTED")
        PreviewFileWarningCode warningCode,
    @Schema(
            description = "Previous matching import metadata. Null when alreadyImported is false.",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
        PreviousFileImportResponse previousImport) {

  /** Creates a file import status response from a service-layer DTO. */
  public static PreviewFileImportStatusResponse from(
      PreviewFileImportStatus previewFileImportStatus) {
    var previousImport =
        previewFileImportStatus.previousImport() == null
            ? null
            : PreviousFileImportResponse.from(previewFileImportStatus.previousImport());
    return new PreviewFileImportStatusResponse(
        previewFileImportStatus.alreadyImported(),
        from(previewFileImportStatus.warningCode()),
        previousImport);
  }

  private static PreviewFileWarningCode from(
      org.budgetanalyzer.transaction.service.dto.PreviewFileWarningCode warningCode) {
    return warningCode == null ? null : PreviewFileWarningCode.valueOf(warningCode.name());
  }
}
