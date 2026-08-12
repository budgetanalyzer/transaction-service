package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.PreviewFileResult;

/** Preview result for one uploaded source file. */
@Schema(description = "Preview result for one uploaded source file")
public record PreviewFileResponse(
    @Schema(
            description = "Original filename of the uploaded file",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "cap-one-2024.csv")
        String sourceFile,
    @Schema(
            description = "Statement format ID used for parsing",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "123")
        Long statementFormatId,
    @Schema(
            description =
                "Opaque encrypted source-file token required for token-backed batch import",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "v2.dGVzdGl2MTIzNDU.Kc4WwTqfh1sFD8pxVq7Hxg")
        String previewImportToken,
    @Schema(
            description =
                "File-level import history status for the uploaded bytes and authenticated user",
            requiredMode = Schema.RequiredMode.REQUIRED)
        PreviewFileImportStatusResponse fileImport,
    @Schema(
            description =
                "Extracted transactions ready for review, including advisory duplicate metadata",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<PreviewTransactionResponse> transactions) {

  /** Creates a response from a service-layer per-file preview result. */
  public static PreviewFileResponse from(PreviewFileResult previewFileResult) {
    return new PreviewFileResponse(
        previewFileResult.sourceFile(),
        previewFileResult.statementFormatId(),
        previewFileResult.previewImportToken(),
        PreviewFileImportStatusResponse.from(previewFileResult.fileImport()),
        previewFileResult.transactions().stream().map(PreviewTransactionResponse::from).toList());
  }
}
