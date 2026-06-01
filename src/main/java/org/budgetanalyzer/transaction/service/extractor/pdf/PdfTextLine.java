package org.budgetanalyzer.transaction.service.extractor.pdf;

import java.util.List;

/** A normalized line extracted from a text-based PDF page. */
public record PdfTextLine(int pageNumber, int lineNumber, float y, List<PdfTextCell> cells) {

  /**
   * Returns the line text reconstructed from normalized cells.
   *
   * @return reconstructed line text
   */
  public String text() {
    return cells.stream()
        .map(PdfTextCell::text)
        .reduce((left, right) -> left + " " + right)
        .orElse("")
        .strip();
  }
}
