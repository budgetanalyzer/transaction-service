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
  void validate_withSignedAmountConfigReturnsNoErrors() {
    var pdfTextTableParserConfig =
        new PdfTextTableParserConfig(
            PdfTextTableFileType.TEXT_PDF,
            List.of("Date", "Description", "Amount"),
            3,
            "Date",
            "MMM d",
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
  void validate_withDebitCreditColumnsReturnsNoErrors() {
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
  void validate_withMissingRequiredFieldsReturnsFieldErrors() {
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
  void validateOrThrow_withInvalidConfigThrowsBusinessException() {
    assertThatThrownBy(() -> pdfTextTableParserConfigValidator.validateOrThrow(null))
        .isInstanceOf(BusinessException.class)
        .hasMessage("PDF text-table parser configuration validation failed.");
  }
}
