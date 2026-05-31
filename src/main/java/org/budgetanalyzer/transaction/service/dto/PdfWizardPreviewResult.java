package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/** Read-only parsed transaction preview returned by the PDF wizard. */
public record PdfWizardPreviewResult(
    List<PreviewTransaction> transactions, List<String> diagnostics) {}
