package org.budgetanalyzer.transaction.service.extractor.pdf;

import java.util.List;

/** Normalized text extracted from a text-based PDF statement sample. */
public record PdfTextDocument(
    List<PdfTextPage> pages, List<PdfTextTableCandidate> tableCandidates) {}
