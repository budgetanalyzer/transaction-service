package org.budgetanalyzer.transaction.service;

import io.swagger.v3.oas.annotations.media.Schema;

/** Error codes for Budget Analyzer API business exceptions. */
@Schema(description = "Error codes for API responses")
public enum BudgetAnalyzerError {
  @Schema(description = "The selected statement format does not have a supported parser revision")
  FORMAT_NOT_SUPPORTED,
  @Schema(description = "Error encountered parsing csv file")
  CSV_PARSING_ERROR,
  @Schema(description = "CSV statement format wizard mapping validation failed")
  CSV_WIZARD_VALIDATION_FAILED,
  @Schema(description = "The uploaded statement file is missing its original filename")
  MISSING_ORIGINAL_FILENAME,
  @Schema(
      description =
          "Transaction date is prior to year 2000. Transactions before 2000 are not supported due"
              + " to EUR exchange rate limitations and 2-digit year format ambiguity.")
  TRANSACTION_DATE_TOO_OLD,
  @Schema(
      description =
          "Transaction date is more than 1 day in the future. Future-dated transactions are not"
              + " allowed to prevent data entry errors.")
  TRANSACTION_DATE_TOO_FAR_IN_FUTURE,
  @Schema(
      description =
          "File with identical content has already been imported by this user. Duplicate file"
              + " imports are blocked to prevent data duplication.")
  FILE_ALREADY_IMPORTED,
  @Schema(description = "Error encountered parsing PDF file")
  PDF_PARSING_ERROR,
  @Schema(
      description =
          "One or more transactions in the batch failed business validation. "
              + "Check the fieldErrors array for details on each failed transaction.")
  BATCH_VALIDATION_FAILED,
  @Schema(description = "A statement format with the given legacy format key already exists")
  FORMAT_KEY_ALREADY_EXISTS,
  @Schema(description = "The statement format was not found")
  FORMAT_NOT_FOUND,
  @Schema(description = "The preview import token is invalid or incomplete")
  PREVIEW_IMPORT_TOKEN_INVALID,
  @Schema(description = "The preview import token has expired")
  PREVIEW_IMPORT_TOKEN_EXPIRED,
  @Schema(
      description =
          "Batch import completed validation and duplicate filtering without any rows to create")
  BATCH_IMPORT_NO_TRANSACTIONS_CREATED
}
