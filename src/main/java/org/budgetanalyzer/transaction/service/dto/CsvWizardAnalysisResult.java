package org.budgetanalyzer.transaction.service.dto;

import java.util.List;
import java.util.Map;

/** CSV wizard analysis result for a sample upload. */
public record CsvWizardAnalysisResult(
    List<String> headers,
    List<Map<String, String>> sampleRows,
    CsvWizardColumnMapping inferredMapping,
    double confidence,
    Map<String, Double> columnConfidences,
    List<CsvWizardValidationWarning> warnings) {}
