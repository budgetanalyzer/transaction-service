package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/** Service-layer result of previewing an ordered group of statement files before import. */
public record PreviewResult(List<PreviewFileResult> files) {

  /** Creates an immutable grouped preview result. */
  public PreviewResult {
    files = List.copyOf(files);
  }
}
