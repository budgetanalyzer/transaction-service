package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/** Read-only parsed transaction preview returned by the CSV wizard. */
public record CsvWizardPreviewResult(
    List<PreviewTransaction> transactions, List<CsvWizardValidationWarning> warnings) {}
