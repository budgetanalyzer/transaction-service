package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableFileType;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableParserConfig;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableYearSource;

class PdfTextTableParserConfigValidatorTest {

  private final PdfTextTableParserConfigValidator pdfTextTableParserConfigValidator =
      new PdfTextTableParserConfigValidator();

  @Test
  void validateWithSignedAmountConfigReturnsNoErrors() {
    var pdfTextTableParserConfig =
        new PdfTextTableParserConfig(
            PdfTextTableFileType.TEXT_PDF,
            List.of("Date", "Description", "Amount"),
            3,
            "Date",
            "MM/dd",
            "Description",
            "Amount",
            null,
            null,
            null,
            PdfTextTableNegativeMeans.CREDIT,
            PdfTextTableYearSource.STATEMENT_PERIOD);

    var fieldErrors = pdfTextTableParserConfigValidator.validate(pdfTextTableParserConfig);

    assertThat(fieldErrors).isEmpty();
  }

  @Test
  void validateWithSignedAmountAndTypeHeaderDoesNotRequireNegativeMeans() {
    var pdfTextTableParserConfig =
        new PdfTextTableParserConfig(
            PdfTextTableFileType.TEXT_PDF,
            List.of("Date", "Description", "Amount", "Type"),
            3,
            "Date",
            "MM/dd/uuuu",
            "Description",
            "Amount",
            null,
            null,
            "Type",
            null,
            PdfTextTableYearSource.EXPLICIT_DATE);

    var fieldErrors = pdfTextTableParserConfigValidator.validate(pdfTextTableParserConfig);

    assertThat(fieldErrors).isEmpty();
  }

  @Test
  void validateWithSignedAmountAndNoDirectionSourceReturnsFieldErrors() {
    var pdfTextTableParserConfig =
        new PdfTextTableParserConfig(
            PdfTextTableFileType.TEXT_PDF,
            List.of("Date", "Description", "Amount"),
            3,
            "Date",
            "MM/dd/uuuu",
            "Description",
            "Amount",
            null,
            null,
            null,
            null,
            PdfTextTableYearSource.EXPLICIT_DATE);

    var fieldErrors = pdfTextTableParserConfigValidator.validate(pdfTextTableParserConfig);

    assertThat(fieldErrors).extracting("field").containsExactly("negativeMeans");
  }

  @Test
  void validateWithDebitCreditColumnsReturnsNoErrors() {
    var pdfTextTableParserConfig =
        new PdfTextTableParserConfig(
            PdfTextTableFileType.TEXT_PDF,
            List.of("Date", "Description", "Debit", "Credit"),
            3,
            "Date",
            "MM/dd/uuuu",
            "Description",
            null,
            "Debit",
            "Credit",
            null,
            null,
            PdfTextTableYearSource.EXPLICIT_DATE);

    var fieldErrors = pdfTextTableParserConfigValidator.validate(pdfTextTableParserConfig);

    assertThat(fieldErrors).isEmpty();
  }

  @Test
  void validateWithMissingRequiredFieldsReturnsFieldErrors() {
    var pdfTextTableParserConfig =
        new PdfTextTableParserConfig(
            PdfTextTableFileType.TEXT_PDF,
            List.of(),
            0,
            null,
            "yyyyMMdd",
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    var fieldErrors = pdfTextTableParserConfigValidator.validate(pdfTextTableParserConfig);

    assertThat(fieldErrors)
        .extracting("field")
        .contains(
            "headerMustContain",
            "minimumRows",
            "dateHeader",
            "dateFormat",
            "descriptionHeader",
            "debitHeader",
            "creditHeader",
            "yearSource");
  }

  @Test
  void validateOrThrowWithInvalidConfigThrowsBusinessException() {
    assertThatThrownBy(() -> pdfTextTableParserConfigValidator.validateOrThrow(null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            businessException -> {
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.STATEMENT_FORMAT_VALIDATION_FAILED.name());
              assertThat(businessException.getFieldErrors())
                  .singleElement()
                  .satisfies(
                      fieldError -> {
                        assertThat(fieldError.getIndex()).isNull();
                        assertThat(fieldError.getField()).isEqualTo("parserConfig");
                        assertThat(fieldError.getRejectedValue()).isNull();
                      });
            });
  }
}
