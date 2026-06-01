package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/** Analysis result for a text-PDF statement format wizard sample. */
public record PdfWizardAnalysisResult(
    List<PdfWizardTableCandidate> candidates, double confidence, List<String> rejectionReasons) {}
