package org.budgetanalyzer.transaction.service;

import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.budgetanalyzer.service.api.FieldError;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableFileType;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableParserConfig;

/** Validates deterministic text-PDF table parser configurations. */
public class PdfTextTableParserConfigValidator {

  private static final int MINIMUM_ALLOWED_ROWS = 1;
  private static final List<String> SUPPORTED_DATE_FORMATS =
      List.of(
          "MM/dd/uu",
          "M/d/uu",
          "MM/dd/uuuu",
          "M/d/uuuu",
          "dd/MM/uu",
          "d/M/uu",
          "dd/MM/uuuu",
          "d/M/uuuu",
          "uuuu-MM-dd",
          "MMM d",
          "MMM dd",
          "MMM d uuuu",
          "MMM dd uuuu",
          "d MMM uuuu",
          "dd MMM uuuu");

  /**
   * Validates a parser configuration and returns field-addressable errors.
   *
   * @param pdfTextTableParserConfig parser configuration
   * @return validation errors
   */
  public List<FieldError> validate(PdfTextTableParserConfig pdfTextTableParserConfig) {
    var fieldErrors = new ArrayList<FieldError>();
    if (pdfTextTableParserConfig == null) {
      fieldErrors.add(FieldError.of("parserConfig", "PDF text-table config is required.", null));
      return fieldErrors;
    }

    validateFileType(pdfTextTableParserConfig, fieldErrors);
    validateHeaderRules(pdfTextTableParserConfig, fieldErrors);
    validateRequired("dateHeader", pdfTextTableParserConfig.dateHeader(), fieldErrors);
    validateDateFormat(pdfTextTableParserConfig.dateFormat(), fieldErrors);
    validateRequired(
        "descriptionHeader", pdfTextTableParserConfig.descriptionHeader(), fieldErrors);
    validateAmountColumns(pdfTextTableParserConfig, fieldErrors);
    if (pdfTextTableParserConfig.yearSource() == null) {
      fieldErrors.add(FieldError.of("yearSource", "Year source is required.", null));
    }
    return fieldErrors;
  }

  /**
   * Validates a parser configuration and throws a business exception if invalid.
   *
   * @param pdfTextTableParserConfig parser configuration
   */
  public void validateOrThrow(PdfTextTableParserConfig pdfTextTableParserConfig) {
    var fieldErrors = validate(pdfTextTableParserConfig);
    if (!fieldErrors.isEmpty()) {
      throw new BusinessException(
          "PDF text-table parser configuration validation failed.",
          BudgetAnalyzerError.STATEMENT_FORMAT_VALIDATION_FAILED.name(),
          fieldErrors);
    }
  }

  private void validateFileType(
      PdfTextTableParserConfig pdfTextTableParserConfig, List<FieldError> fieldErrors) {
    if (pdfTextTableParserConfig.fileType() == null) {
      fieldErrors.add(FieldError.of("fileType", "File type is required.", null));
      return;
    }
    if (pdfTextTableParserConfig.fileType() != PdfTextTableFileType.TEXT_PDF) {
      fieldErrors.add(
          FieldError.of(
              "fileType",
              "Only text-based PDF parser configurations are supported.",
              pdfTextTableParserConfig.fileType().name()));
    }
  }

  private void validateHeaderRules(
      PdfTextTableParserConfig pdfTextTableParserConfig, List<FieldError> fieldErrors) {
    if (pdfTextTableParserConfig.headerMustContain() == null
        || pdfTextTableParserConfig.headerMustContain().isEmpty()) {
      fieldErrors.add(
          FieldError.of(
              "headerMustContain",
              "At least one required table header token is required.",
              pdfTextTableParserConfig.headerMustContain()));
    } else if (pdfTextTableParserConfig.headerMustContain().stream().anyMatch(this::isBlank)) {
      fieldErrors.add(
          FieldError.of(
              "headerMustContain",
              "Required table header tokens must not be blank.",
              pdfTextTableParserConfig.headerMustContain()));
    }

    if (pdfTextTableParserConfig.minimumRows() == null
        || pdfTextTableParserConfig.minimumRows() < MINIMUM_ALLOWED_ROWS) {
      fieldErrors.add(
          FieldError.of(
              "minimumRows",
              "Minimum rows must be at least one.",
              pdfTextTableParserConfig.minimumRows()));
    }
  }

  private void validateDateFormat(String dateFormat, List<FieldError> fieldErrors) {
    validateRequired("dateFormat", dateFormat, fieldErrors);
    if (isBlank(dateFormat)) {
      return;
    }
    if (!SUPPORTED_DATE_FORMATS.contains(dateFormat)) {
      fieldErrors.add(
          FieldError.of(
              "dateFormat",
              "Date format is not supported by the PDF text-table parser.",
              dateFormat));
      return;
    }
    try {
      DateTimeFormatter.ofPattern(dateFormat, Locale.ROOT).withResolverStyle(ResolverStyle.SMART);
    } catch (IllegalArgumentException illegalArgumentException) {
      fieldErrors.add(FieldError.of("dateFormat", "Date format pattern is invalid.", dateFormat));
    }
  }

  private void validateAmountColumns(
      PdfTextTableParserConfig pdfTextTableParserConfig, List<FieldError> fieldErrors) {
    var hasSignedAmountColumn = !isBlank(pdfTextTableParserConfig.amountHeader());
    var hasDebitCreditColumns =
        !isBlank(pdfTextTableParserConfig.debitHeader())
            || !isBlank(pdfTextTableParserConfig.creditHeader());

    if (hasSignedAmountColumn && hasDebitCreditColumns) {
      fieldErrors.add(
          FieldError.of(
              "amountHeader",
              "Use either a signed amount column or separate debit and credit columns.",
              pdfTextTableParserConfig.amountHeader()));
      return;
    }
    if (hasSignedAmountColumn) {
      if (pdfTextTableParserConfig.negativeMeans() == null) {
        fieldErrors.add(
            FieldError.of(
                "negativeMeans",
                "A signed amount column requires a negative amount direction.",
                null));
      }
      return;
    }

    validateRequired("debitHeader", pdfTextTableParserConfig.debitHeader(), fieldErrors);
    validateRequired("creditHeader", pdfTextTableParserConfig.creditHeader(), fieldErrors);
  }

  private void validateRequired(String field, String value, List<FieldError> fieldErrors) {
    if (isBlank(value)) {
      fieldErrors.add(FieldError.of(field, "Field is required.", value));
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
