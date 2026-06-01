package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.PdfWizardAnalysisResult;

/** Response DTO for PDF wizard sample analysis. */
@Schema(description = "PDF wizard sample analysis response")
public record PdfWizardAnalysisResponse(
    List<PdfWizardTableCandidateResponse> candidates,
    double confidence,
    List<String> rejectionReasons) {

  /** Creates a response from a service-layer PDF wizard analysis result. */
  public static PdfWizardAnalysisResponse from(PdfWizardAnalysisResult pdfWizardAnalysisResult) {
    return new PdfWizardAnalysisResponse(
        pdfWizardAnalysisResult.candidates().stream()
            .map(PdfWizardTableCandidateResponse::from)
            .toList(),
        pdfWizardAnalysisResult.confidence(),
        pdfWizardAnalysisResult.rejectionReasons());
  }
}
