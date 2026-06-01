package org.budgetanalyzer.transaction.service.extractor.pdf;

/** A normalized cell extracted from a text-based PDF line. */
public record PdfTextCell(
    int pageNumber, int lineNumber, int columnIndex, String text, float startX, float endX) {}
