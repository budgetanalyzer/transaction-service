package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import org.budgetanalyzer.core.csv.CsvData;
import org.budgetanalyzer.core.csv.CsvParser;
import org.budgetanalyzer.core.csv.impl.OpenCsvParser;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.CsvWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.CsvWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.CsvWizardMappingPreviewCommand;

class CsvStatementFormatWizardServiceTest {

  private final CsvStatementFormatWizardService csvStatementFormatWizardService =
      new CsvStatementFormatWizardService(new OpenCsvParser(), null);

  @Test
  void analyzeInfersSingleAmountWithTypeMapping() {
    var result =
        csvStatementFormatWizardService.analyze(
            csv(
                """
                Transaction Date,Transaction Description,Transaction Amount,Transaction Type,Category
                04/12/24,Coffee Shop,4.50,Debit,Dining
                04/13/24,Payment,100.00,Credit,Payment
                """),
            "sample.csv");

    assertThat(result.inferredMapping().dateColumn()).isEqualTo("Transaction Date");
    assertThat(result.inferredMapping().dateFormat()).isEqualTo("MM/dd/uu");
    assertThat(result.inferredMapping().descriptionColumn()).isEqualTo("Transaction Description");
    assertThat(result.inferredMapping().amountMode())
        .isEqualTo(CsvWizardAmountMode.SINGLE_AMOUNT_WITH_TYPE);
    assertThat(result.inferredMapping().amountColumn()).isEqualTo("Transaction Amount");
    assertThat(result.inferredMapping().typeColumn()).isEqualTo("Transaction Type");
    assertThat(result.confidence()).isGreaterThan(0.7);
    assertThat(result.sampleRows()).hasSize(2);
  }

  @Test
  void analyzeInfersDebitCreditColumnMapping() {
    var result =
        csvStatementFormatWizardService.analyze(
            csv(
                """
                Date,Particulars,Withdrawal,Deposit
                15/11/24,Coffee Shop,150.00,
                14/11/24,Transfer,,5000.00
                """),
            "sample.csv");

    assertThat(result.inferredMapping().amountMode())
        .isEqualTo(CsvWizardAmountMode.DEBIT_CREDIT_COLUMNS);
    assertThat(result.inferredMapping().debitColumn()).isEqualTo("Withdrawal");
    assertThat(result.inferredMapping().creditColumn()).isEqualTo("Deposit");
    assertThat(result.inferredMapping().dateFormat()).isEqualTo("dd/MM/uu");
  }

  @Test
  void analyzeInfersDateTimeMappingWhenRowsMixDateTimeAndDateOnly() {
    var result =
        csvStatementFormatWizardService.analyze(
            csv(
                """
                ,Date,Description,Debit,Credit,Balance,Channel,
                " ","31 Dec 2025 10:37","Payment for Goods /Services","379.00","","37,607.41","MOB",
                " ","25 Dec 2025","Interest Credit","","22.91","52,379.85","AUTO",
                """),
            "bkk-bank-dec-17-31.csv");

    assertThat(result.inferredMapping().dateColumn()).isEqualTo("Date");
    assertThat(result.inferredMapping().dateFormat()).isEqualTo("d MMM uuuu HH:mm");
    assertThat(result.inferredMapping().amountMode())
        .isEqualTo(CsvWizardAmountMode.DEBIT_CREDIT_COLUMNS);
    assertThat(result.warnings()).extracting("field").doesNotContain("dateFormat");
  }

  @Test
  void analyzeReturnsWarningsWhenSampleHasNoUsableColumns() {
    var result =
        csvStatementFormatWizardService.analyze(
            csv(
                """
                Alpha,Beta
                one,two
                """),
            "sample.csv");

    assertThat(result.inferredMapping().amountMode()).isNull();
    assertThat(result.inferredMapping().dateColumn()).isNull();
    assertThat(result.warnings()).extracting("field").contains("dateColumn", "amountMode");
    assertThat(result.confidence()).isEqualTo(0.0);
  }

  @Test
  void analyzeReturnsFileWarningsForEmptyCsv() {
    var result = csvStatementFormatWizardService.analyze(csv(""), "empty.csv");

    assertThat(result.headers()).isEmpty();
    assertThat(result.warnings()).extracting("field").contains("file", "dateColumn", "amountMode");
  }

  @Test
  void previewReturnsReadOnlyParsedRows() {
    var command =
        new CsvWizardMappingPreviewCommand("Example Bank", "USD", "checking-001", singleMapping());

    var result =
        csvStatementFormatWizardService.preview(
            csv(
                """
                Transaction Date,Description,Amount,Type
                04/12/24,Coffee Shop,4.50,Debit
                """),
            "sample.csv",
            command);

    assertThat(result.transactions()).hasSize(1);
    assertThat(result.transactions().getFirst().description()).isEqualTo("Coffee Shop");
    assertThat(result.transactions().getFirst().type()).isEqualTo(TransactionType.DEBIT);
    assertThat(result.transactions().getFirst().bankName()).isEqualTo("Example Bank");
    assertThat(result.transactions().getFirst().currencyIsoCode()).isEqualTo("USD");
    assertThat(result.transactions().getFirst().accountId()).isEqualTo("checking-001");
  }

  @Test
  void previewSupportsDebitCreditColumnMappings() {
    var command =
        new CsvWizardMappingPreviewCommand(
            "Bangkok Bank", "THB", "checking-001", debitCreditMapping());

    var result =
        csvStatementFormatWizardService.preview(
            csv(
                """
                Date,Particulars,Withdrawal,Deposit
                15/11/24,Coffee Shop,150.00,
                14/11/24,Transfer,,5000.00
                """),
            "sample.csv",
            command);

    assertThat(result.transactions()).hasSize(2);
    assertThat(result.transactions().get(0).type()).isEqualTo(TransactionType.DEBIT);
    assertThat(result.transactions().get(1).type()).isEqualTo(TransactionType.CREDIT);
    assertThat(result.transactions().get(1).currencyIsoCode()).isEqualTo("THB");
  }

  @Test
  void previewSupportsBangkokBankDateTimeRowsWithDateOnlyFallback() {
    var mapping =
        new CsvWizardColumnMapping(
            "Date",
            "d MMM uuuu HH:mm",
            "Description",
            CsvWizardAmountMode.DEBIT_CREDIT_COLUMNS,
            null,
            "Debit",
            "Credit",
            null,
            null);
    var command =
        new CsvWizardMappingPreviewCommand("Bangkok Bank", "THB", "checking-001", mapping);

    var result =
        csvStatementFormatWizardService.preview(
            csv(
                """
                ,Date,Description,Debit,Credit,Balance,Channel,
                " ","31 Dec 2025 10:37","Payment for Goods /Services","379.00","","37,607.41","MOB",
                " ","25 Dec 2025","Interest Credit","","22.91","52,379.85","AUTO",
                """),
            "bkk-bank-dec-17-31.csv",
            command);

    assertThat(result.transactions()).hasSize(2);
    assertThat(result.transactions().get(0).date()).isEqualTo(LocalDate.of(2025, 12, 31));
    assertThat(result.transactions().get(0).type()).isEqualTo(TransactionType.DEBIT);
    assertThat(result.transactions().get(1).date()).isEqualTo(LocalDate.of(2025, 12, 25));
    assertThat(result.transactions().get(1).type()).isEqualTo(TransactionType.CREDIT);
  }

  @Test
  void previewRejectsInvalidMappingWithFieldErrors() {
    var invalidMapping =
        new CsvWizardColumnMapping(
            "Transaction Date",
            "MM/dd/uu",
            "Description",
            CsvWizardAmountMode.SINGLE_AMOUNT_WITH_TYPE,
            "Amount",
            null,
            null,
            null,
            null);
    var command =
        new CsvWizardMappingPreviewCommand("Example Bank", "USD", "checking-001", invalidMapping);

    assertThatThrownBy(
            () ->
                csvStatementFormatWizardService.preview(
                    csv(
                        """
                        Transaction Date,Description,Amount,Type
                        04/12/24,Coffee Shop,4.50,Debit
                        """),
                    "sample.csv",
                    command))
        .isInstanceOfSatisfying(
            BusinessException.class,
            businessException -> {
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.CSV_WIZARD_VALIDATION_FAILED.name());
              assertThat(businessException.getFieldErrors())
                  .extracting("field")
                  .contains("mapping.typeColumn");
            });
  }

  @Test
  void previewRejectsUnknownColumnsAndInvalidCurrencyWithFieldErrors() {
    var invalidMapping =
        new CsvWizardColumnMapping(
            "Missing Date",
            "not-a-format",
            "Missing Description",
            CsvWizardAmountMode.DEBIT_CREDIT_COLUMNS,
            null,
            "Missing Debit",
            "Missing Credit",
            null,
            "Missing Category");
    var command = new CsvWizardMappingPreviewCommand("Example Bank", "BAD", null, invalidMapping);

    assertThatThrownBy(
            () ->
                csvStatementFormatWizardService.preview(
                    csv(
                        """
                        Date,Description,Debit,Credit
                        04/12/24,Coffee Shop,4.50,
                        """),
                    "sample.csv",
                    command))
        .isInstanceOfSatisfying(
            BusinessException.class,
            businessException ->
                assertThat(businessException.getFieldErrors())
                    .extracting("field")
                    .contains(
                        "defaultCurrencyIsoCode",
                        "mapping.dateColumn",
                        "mapping.dateFormat",
                        "mapping.descriptionColumn",
                        "mapping.debitColumn",
                        "mapping.creditColumn",
                        "mapping.categoryColumn"));
  }

  @Test
  void previewRejectsParserErrorsWithFieldAddressableError() {
    var command =
        new CsvWizardMappingPreviewCommand("Example Bank", "USD", "checking-001", singleMapping());

    assertThatThrownBy(
            () ->
                csvStatementFormatWizardService.preview(
                    csv(
                        """
                        Transaction Date,Description,Amount,Type
                        not-a-date,Coffee Shop,4.50,Debit
                        """),
                    "sample.csv",
                    command))
        .isInstanceOfSatisfying(
            BusinessException.class,
            businessException ->
                assertThat(businessException.getFieldErrors())
                    .extracting("field")
                    .contains("mapping.dateColumn"));
  }

  @Test
  void previewRejectsEmptyDataRows() {
    var command =
        new CsvWizardMappingPreviewCommand("Example Bank", "USD", "checking-001", singleMapping());

    assertThatThrownBy(
            () ->
                csvStatementFormatWizardService.preview(
                    csv("Transaction Date,Description,Amount,Type\n"), "sample.csv", command))
        .isInstanceOfSatisfying(
            BusinessException.class,
            businessException ->
                assertThat(businessException.getFieldErrors())
                    .extracting("field")
                    .contains("file"));
  }

  @Test
  void analyzeWrapsCsvParserIoFailures() {
    var failingCsvParser =
        new CsvParser() {
          @Override
          public CsvData parseCsvInputStream(
              InputStream inputStream, String fileName, String format) throws IOException {
            throw new IOException("read failed");
          }
        };
    var service = new CsvStatementFormatWizardService(failingCsvParser, null);

    assertThatThrownBy(() -> service.analyze(csv("Date\n"), "sample.csv"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            businessException ->
                assertThat(businessException.getCode())
                    .isEqualTo(BudgetAnalyzerError.CSV_PARSING_ERROR.name()));
  }

  private CsvWizardColumnMapping singleMapping() {
    return new CsvWizardColumnMapping(
        "Transaction Date",
        "MM/dd/uu",
        "Description",
        CsvWizardAmountMode.SINGLE_AMOUNT_WITH_TYPE,
        "Amount",
        null,
        null,
        "Type",
        null);
  }

  private CsvWizardColumnMapping debitCreditMapping() {
    return new CsvWizardColumnMapping(
        "Date",
        "dd/MM/uu",
        "Particulars",
        CsvWizardAmountMode.DEBIT_CREDIT_COLUMNS,
        null,
        "Withdrawal",
        "Deposit",
        null,
        null);
  }

  private byte[] csv(String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }
}
