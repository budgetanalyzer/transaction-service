package org.budgetanalyzer.transaction.service.dto;

/** Service-layer command to save a user-scoped CSV wizard statement format. */
public record CsvWizardSaveCommand(
    String displayName,
    String bankName,
    String defaultCurrencyIsoCode,
    CsvWizardColumnMapping mapping) {}
