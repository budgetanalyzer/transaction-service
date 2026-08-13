package org.budgetanalyzer.transaction.api.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.BatchFileImportSource;
import org.budgetanalyzer.transaction.service.dto.BatchImportFile;
import org.budgetanalyzer.transaction.service.dto.PreviewImportToken;

/** Reviewed transactions and preview token for one source file in a grouped batch import. */
@Schema(description = "Reviewed batch import data for one previewed source file")
public record BatchImportFileRequest(
    @Schema(
            description = "Opaque source-file token returned by the preview endpoint",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "v2.dGVzdGl2MTIzNDU.Kc4WwTqfh1sFD8pxVq7Hxg")
        @NotBlank(message = "previewImportToken is required")
        String previewImportToken,
    @Schema(
            description =
                "Required reviewed transactions from this source file; the list may be empty",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "transactions list is required")
        List<@NotNull(message = "transaction is required") @Valid BatchImportTransactionRequest>
            transactions) {

  /** Converts this request and its verified token to a service-layer file group. */
  public BatchImportFile toServiceFile(PreviewImportToken previewImportToken) {
    return new BatchImportFile(
        BatchFileImportSource.from(previewImportToken),
        transactions.stream().map(BatchImportTransactionRequest::toServiceTransaction).toList());
  }
}
