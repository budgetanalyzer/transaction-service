package org.budgetanalyzer.transaction.service.dto;

/** User-confirmed CSV wizard column mapping. */
public record CsvWizardColumnMapping(
    String dateColumn,
    String dateFormat,
    String descriptionColumn,
    CsvWizardAmountMode amountMode,
    String amountColumn,
    String debitColumn,
    String creditColumn,
    String typeColumn,
    String categoryColumn) {}
