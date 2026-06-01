package org.budgetanalyzer.transaction.service.dto;

import java.util.List;
import java.util.Map;

/** Ranked transaction-table candidate inferred from a text-based PDF sample. */
public record PdfWizardTableCandidate(
    String candidateId,
    int pageNumber,
    int startLineNumber,
    int endLineNumber,
    int rowCount,
    int repeatedHeaderCount,
    List<String> headers,
    List<List<String>> sampleRows,
    PdfWizardColumnMapping inferredMapping,
    double confidence,
    Map<String, Double> columnConfidences,
    List<String> rejectionReasons) {}
