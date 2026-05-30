package org.budgetanalyzer.transaction.api.response;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.CsvWizardAnalysisResult;

/** Response DTO for CSV wizard sample analysis. */
@Schema(description = "CSV wizard sample analysis response")
public record CsvWizardAnalysisResponse(
    List<String> headers,
    List<Map<String, String>> sampleRows,
    CsvWizardColumnMappingResponse inferredMapping,
    double confidence,
    Map<String, Double> columnConfidences,
    List<CsvWizardWarningResponse> warnings) {

  /** Creates a response from a service-layer analysis result. */
  public static CsvWizardAnalysisResponse from(CsvWizardAnalysisResult csvWizardAnalysisResult) {
    return new CsvWizardAnalysisResponse(
        csvWizardAnalysisResult.headers(),
        csvWizardAnalysisResult.sampleRows(),
        CsvWizardColumnMappingResponse.from(csvWizardAnalysisResult.inferredMapping()),
        csvWizardAnalysisResult.confidence(),
        csvWizardAnalysisResult.columnConfidences(),
        csvWizardAnalysisResult.warnings().stream().map(CsvWizardWarningResponse::from).toList());
  }
}
