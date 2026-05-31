package org.budgetanalyzer.transaction.service.extractor.pdf;

import java.util.List;

/** A coarse table-like block detected in normalized PDF text. */
public record PdfTextTableCandidate(
    int pageNumber,
    int startLineNumber,
    int endLineNumber,
    List<String> headerCells,
    List<List<String>> dataRows,
    List<List<String>> sampleRows,
    int rowCount,
    int repeatedHeaderCount) {}
