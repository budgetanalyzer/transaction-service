package org.budgetanalyzer.transaction.api.response;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.PdfWizardTableCandidate;

/** Response DTO for a ranked PDF wizard table candidate. */
@Schema(description = "Ranked PDF wizard table candidate")
public record PdfWizardTableCandidateResponse(
    String candidateId,
    int pageNumber,
    int startLineNumber,
    int endLineNumber,
    int rowCount,
    int repeatedHeaderCount,
    List<String> headers,
    List<List<String>> sampleRows,
    PdfWizardColumnMappingResponse inferredMapping,
    double confidence,
    Map<String, Double> columnConfidences,
    List<String> rejectionReasons) {

  /** Creates a response from a service-layer PDF wizard table candidate. */
  public static PdfWizardTableCandidateResponse from(
      PdfWizardTableCandidate pdfWizardTableCandidate) {
    return new PdfWizardTableCandidateResponse(
        pdfWizardTableCandidate.candidateId(),
        pdfWizardTableCandidate.pageNumber(),
        pdfWizardTableCandidate.startLineNumber(),
        pdfWizardTableCandidate.endLineNumber(),
        pdfWizardTableCandidate.rowCount(),
        pdfWizardTableCandidate.repeatedHeaderCount(),
        pdfWizardTableCandidate.headers(),
        pdfWizardTableCandidate.sampleRows(),
        PdfWizardColumnMappingResponse.from(pdfWizardTableCandidate.inferredMapping()),
        pdfWizardTableCandidate.confidence(),
        pdfWizardTableCandidate.columnConfidences(),
        pdfWizardTableCandidate.rejectionReasons());
  }
}
