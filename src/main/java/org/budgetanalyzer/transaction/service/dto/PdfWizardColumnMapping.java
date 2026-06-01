package org.budgetanalyzer.transaction.service.dto;

/** Inferred column mapping for a text-PDF table wizard candidate. */
public record PdfWizardColumnMapping(
    String dateHeader,
    String dateFormat,
    String descriptionHeader,
    PdfWizardAmountMode amountMode,
    String amountHeader,
    String debitHeader,
    String creditHeader,
    String typeHeader,
    PdfTextTableNegativeMeans negativeMeans) {}
