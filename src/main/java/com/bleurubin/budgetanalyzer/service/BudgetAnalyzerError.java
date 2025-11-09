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
  CSV_PARSING_ERROR,
  @Schema(
      description =
          "Transaction date is prior to year 2000. Transactions before 2000 are not supported due"
              + " to EUR exchange rate limitations and 2-digit year format ambiguity.")
  TRANSACTION_DATE_TOO_OLD,
  @Schema(
      description =
          "Transaction date is more than 1 day in the future. Future-dated transactions are not"
              + " allowed to prevent data entry errors.")
  TRANSACTION_DATE_TOO_FAR_IN_FUTURE
}
