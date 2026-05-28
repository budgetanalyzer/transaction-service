package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/** Service-layer result of previewing a statement file before import. */
public record PreviewResult(
    String sourceFile,
    Long statementFormatId,
    String previewImportToken,
    PreviewFileImportStatus fileImport,
    List<PreviewTransaction> transactions) {

  /**
   * Legacy constructor that ignores the removed detected format key.
   *
   * @param sourceFile original filename
   * @param detectedFormat legacy detected format key
   * @param previewImportToken encrypted preview import token
   * @param fileImport file import status
   * @param transactions preview transactions
   */
  public PreviewResult(
      String sourceFile,
      String detectedFormat,
      String previewImportToken,
      PreviewFileImportStatus fileImport,
      List<PreviewTransaction> transactions) {
    this(sourceFile, 1L, previewImportToken, fileImport, transactions);
  }

  /**
   * Legacy accessor for tests that still reference the removed detected format field.
   *
   * @return string representation of the statement format ID
   */
  public String detectedFormat() {
    return statementFormatId == null ? null : statementFormatId.toString();
  }
}
