package org.budgetanalyzer.transaction.service.dto;

/** Service-layer warning for a previewed transaction field. */
public record PreviewWarning(int index, String field, String message) {}
