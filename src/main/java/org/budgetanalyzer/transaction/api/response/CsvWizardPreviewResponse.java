package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.CsvWizardPreviewResult;

/** Response DTO for read-only CSV wizard parser preview rows. */
@Schema(description = "CSV wizard read-only parser preview response")
public record CsvWizardPreviewResponse(
    List<PreviewTransactionResponse> transactions, List<CsvWizardWarningResponse> warnings) {

  /** Creates a response from a service-layer preview result. */
  public static CsvWizardPreviewResponse from(CsvWizardPreviewResult csvWizardPreviewResult) {
    return new CsvWizardPreviewResponse(
        csvWizardPreviewResult.transactions().stream()
            .map(PreviewTransactionResponse::from)
            .toList(),
        csvWizardPreviewResult.warnings().stream().map(CsvWizardWarningResponse::from).toList());
  }
}
