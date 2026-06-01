package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/** Service-layer command to parse a read-only PDF wizard mapping preview. */
public record PdfWizardMappingPreviewCommand(
    String bankName,
    String defaultCurrencyIsoCode,
    String accountId,
    List<String> headerMustContain,
    Integer minimumRows,
    PdfTextTableYearSource yearSource,
    PdfWizardColumnMapping mapping) {}
