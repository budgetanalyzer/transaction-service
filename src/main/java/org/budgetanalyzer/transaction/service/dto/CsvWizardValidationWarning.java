package org.budgetanalyzer.transaction.service.dto;

/** Non-blocking CSV wizard warning for a field or inferred mapping. */
public record CsvWizardValidationWarning(String field, String message) {}
