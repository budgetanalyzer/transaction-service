package com.bleurubin.budgetanalyzer.service;

import io.swagger.v3.oas.annotations.media.Schema;

/** Error codes for Budget Analyzer API business exceptions. */
@Schema(description = "Error codes for API responses")
public enum BudgetAnalyzerError {
  @Schema(
      description =
          "The parameter 'format' passed to importCsvTransactions doesn't have a csvConfig "
              + "mapping in application.yml")
  CSV_FORMAT_NOT_SUPPORTED,
  @Schema(description = "Error encountered parsing csv file")
  CSV_PARSING_ERROR
}
