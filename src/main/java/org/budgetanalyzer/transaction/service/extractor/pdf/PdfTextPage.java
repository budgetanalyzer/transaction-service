package org.budgetanalyzer.transaction.service.extractor.pdf;

import java.util.List;

/** A normalized text page extracted from a PDF. */
public record PdfTextPage(int pageNumber, List<PdfTextLine> lines) {}
