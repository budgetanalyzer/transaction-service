package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/** Service-layer command to save a user-scoped PDF wizard statement format. */
public record PdfWizardSaveCommand(
    String displayName,
    String bankName,
    String defaultCurrencyIsoCode,
    List<String> headerMustContain,
    Integer minimumRows,
    PdfTextTableYearSource yearSource,
    PdfWizardColumnMapping mapping) {}
