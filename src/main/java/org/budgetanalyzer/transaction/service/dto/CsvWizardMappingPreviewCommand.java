package org.budgetanalyzer.transaction.service.dto;

/** Service-layer command to parse a read-only CSV wizard mapping preview. */
public record CsvWizardMappingPreviewCommand(
    String bankName,
    String defaultCurrencyIsoCode,
    String accountId,
    CsvWizardColumnMapping mapping) {}
