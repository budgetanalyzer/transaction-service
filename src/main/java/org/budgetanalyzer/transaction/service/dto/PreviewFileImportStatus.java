package org.budgetanalyzer.transaction.service.dto;

import java.util.Optional;

import org.budgetanalyzer.transaction.domain.FileImport;

/** Service-layer file import history status for a preview request. */
public record PreviewFileImportStatus(
    boolean alreadyImported,
    PreviewFileWarningCode warningCode,
    PreviousFileImport previousImport) {

  /** Creates a status for a file with no matching previous import. */
  public static PreviewFileImportStatus notPreviouslyImported() {
    return new PreviewFileImportStatus(false, null, null);
  }

  /** Creates a status from the optional previous import lookup result. */
  public static PreviewFileImportStatus from(Optional<FileImport> fileImport) {
    return fileImport
        .map(PreviewFileImportStatus::alreadyImported)
        .orElseGet(PreviewFileImportStatus::notPreviouslyImported);
  }

  private static PreviewFileImportStatus alreadyImported(FileImport fileImport) {
    return new PreviewFileImportStatus(
        true, PreviewFileWarningCode.FILE_ALREADY_IMPORTED, PreviousFileImport.from(fileImport));
  }
}
