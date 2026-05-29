package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/** Service-layer result of previewing a statement file before import. */
public record PreviewResult(
    String sourceFile,
    Long statementFormatId,
    String previewImportToken,
    PreviewFileImportStatus fileImport,
    List<PreviewTransaction> transactions) {}
