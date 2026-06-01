package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.PdfWizardPreviewResult;

/** Response DTO for read-only PDF wizard parser preview rows and diagnostics. */
@Schema(description = "PDF wizard read-only parser preview response")
public record PdfWizardPreviewResponse(
    List<PreviewTransactionResponse> transactions, List<String> diagnostics) {

  /** Creates a response from a service-layer preview result. */
  public static PdfWizardPreviewResponse from(PdfWizardPreviewResult pdfWizardPreviewResult) {
    return new PdfWizardPreviewResponse(
        pdfWizardPreviewResult.transactions().stream()
            .map(PreviewTransactionResponse::from)
            .toList(),
        pdfWizardPreviewResult.diagnostics());
  }
}
