package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.budgetanalyzer.core.csv.CsvData;
import org.budgetanalyzer.core.csv.CsvParser;
import org.budgetanalyzer.core.csv.impl.OpenCsvParser;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.StatementFormatScope;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.CsvWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.CsvWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.CsvWizardMappingPreviewCommand;
import org.budgetanalyzer.transaction.service.dto.CsvWizardSaveCommand;
import org.budgetanalyzer.transaction.service.dto.StatementFormatCommand;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CsvStatementFormatWizardServiceTest {

  @Mock private StatementFormatService statementFormatService;

  private CsvStatementFormatWizardService csvStatementFormatWizardService;

  @BeforeEach
  void setUp() {
    csvStatementFormatWizardService =
        new CsvStatementFormatWizardService(new OpenCsvParser(), statementFormatService);
  }

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
    var command = new CsvWizardMappingPreviewCommand("", "BAD", null, invalidMapping);

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
                        "bankName",
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
    var service = new CsvStatementFormatWizardService(failingCsvParser, statementFormatService);

    assertThatThrownBy(() -> service.analyze(csv("Date\n"), "sample.csv"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            businessException ->
                assertThat(businessException.getCode())
                    .isEqualTo(BudgetAnalyzerError.CSV_PARSING_ERROR.name()));
  }

  @Test
  void saveCreatesUserScopedCsvFormatWithConfirmedMapping() {
    var saved =
        StatementFormat.createCsvFormat("Example CSV", "Example Bank", "USD", "usr_test123");
    when(statementFormatService.createFormat(
            any(StatementFormatCommand.class), eq("usr_test123"), eq(false)))
        .thenReturn(saved);
    var command = new CsvWizardSaveCommand("Example CSV", "Example Bank", "USD", singleMapping());

    var result =
        csvStatementFormatWizardService.save(
            csv(
                """
                Transaction Date,Description,Amount,Type
                04/12/24,Coffee Shop,4.50,Debit
                """),
            "sample.csv",
            command,
            "usr_test123",
            false);

    assertThat(result).isSameAs(saved);
    var captor = ArgumentCaptor.forClass(StatementFormatCommand.class);
    verify(statementFormatService).createFormat(captor.capture(), eq("usr_test123"), eq(false));
    assertThat(captor.getValue().formatType()).isEqualTo(FormatType.CSV);
    assertThat(captor.getValue().scope()).isEqualTo(StatementFormatScope.USER);
    assertThat(captor.getValue().creditHeader()).isEqualTo("Amount");
    assertThat(captor.getValue().debitHeader()).isEqualTo("Amount");
    assertThat(captor.getValue().typeHeader()).isEqualTo("Type");
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
