package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.budgetanalyzer.core.csv.impl.OpenCsvParser;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;
import org.budgetanalyzer.transaction.service.dto.CsvColumnParserConfig;
import org.budgetanalyzer.transaction.service.dto.ParserAttempt;
import org.budgetanalyzer.transaction.service.dto.ParserAttemptStatus;

class ConfigurableCsvStatementExtractorTest {

  private static final String HANDLER_KEY = "statement-format-7-revision-11";

  private ConfigurableCsvStatementExtractor extractor;

  @BeforeEach
  void setUp() {
    extractor =
        createExtractor(
            7L,
            11L,
            "Test Bank - Export",
            "Test Bank",
            "USD",
            new CsvColumnParserConfig(
                "Transaction Date",
                "MM/dd/uu",
                "Transaction Description",
                "Transaction Amount",
                "Transaction Amount",
                "Transaction Type",
                null));
  }

  @Nested
  class Attempt {

    @Test
    void returnsMatchedForCsvFileWithMatchingHeaders() {
      var csvContent =
          "Transaction Date,Transaction Description,Transaction Amount,Transaction Type\n"
              + "01/15/25,Grocery Store,52.34,Debit\n";
      var content = csvContent.getBytes(StandardCharsets.UTF_8);

      var parserAttempt = attempt(extractor, content, "test.csv", "account-123");

      assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.MATCHED);
    }

    @Test
    void returnsNotApplicableForNonCsvFile() {
      var content = "some content".getBytes(StandardCharsets.UTF_8);

      var parserAttempt = attempt(extractor, content, "test.pdf", null);

      assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.NOT_APPLICABLE);
    }

    @Test
    void returnsNotApplicableForNullFilename() {
      var content = "some content".getBytes(StandardCharsets.UTF_8);

      var parserAttempt = attempt(extractor, content, null, null);

      assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.NOT_APPLICABLE);
    }

    @Test
    void returnsNotApplicableForMissingRequiredHeaders() {
      var csvContent = "Date,Description\n";
      var content = csvContent.getBytes(StandardCharsets.UTF_8);

      var parserAttempt = attempt(extractor, content, "test.csv", null);

      assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.NOT_APPLICABLE);
    }

    @Test
    void returnsMatchedWithExtraHeaders() {
      var csvContent =
          "Transaction Date,Transaction Description,Transaction Amount,"
              + "Transaction Type,Extra Column\n"
              + "01/15/25,Grocery Store,52.34,Debit,ignored\n";
      var content = csvContent.getBytes(StandardCharsets.UTF_8);

      var parserAttempt = attempt(extractor, content, "test.csv", "account-123");

      assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.MATCHED);
    }
  }

  @Nested
  class SharedCsvParserHeaderBehavior {

    @Test
    void quotedHeadersAreAcceptedBySingleSharedParserAttempt() {
      var content =
          ("\"Transaction Date\",\"Transaction Description\",\"Transaction Amount\","
                  + "\"Transaction Type\"\n"
                  + "\"01/15/25\",\"Grocery Store\",\"52.34\",\"Debit\"\n")
              .getBytes(StandardCharsets.UTF_8);

      var transactions = matchedTransactions(extractor, content, "account-123");
      assertThat(transactions).hasSize(1);
      assertThat(transactions.getFirst().description()).isEqualTo("Grocery Store");
      assertThat(transactions.getFirst().amount()).isEqualByComparingTo(new BigDecimal("52.34"));
    }

    @Test
    void bomBearingHeadersAreNotApplicableAfterSingleSharedParserAttempt() {
      var content =
          ("\\uFEFFTransaction Date,Transaction Description,Transaction Amount,"
                  + "Transaction Type\n"
                  + "01/15/25,Grocery Store,52.34,Debit\n")
              .replace("\\uFEFF", "\uFEFF")
              .getBytes(StandardCharsets.UTF_8);

      var parserAttempt = attempt(extractor, content, "bom.csv", "account-123");

      assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.NOT_APPLICABLE);
    }

    @Test
    void extraHeadersAreAcceptedAndSharedParserExtractionIgnoresExtraValues() {
      var content =
          ("Transaction Date,Transaction Description,Transaction Amount,Transaction Type,Extra\n"
                  + "01/15/25,Grocery Store,52.34,Debit,ignored\n")
              .getBytes(StandardCharsets.UTF_8);

      var transactions = matchedTransactions(extractor, content, "account-123");
      assertThat(transactions).hasSize(1);
      assertThat(transactions.getFirst().date()).isEqualTo(LocalDate.of(2025, 1, 15));
      assertThat(transactions.getFirst().description()).isEqualTo("Grocery Store");
      assertThat(transactions.getFirst().type()).isEqualTo(TransactionType.DEBIT);
    }
  }

  @Nested
  class Extract {

    @Test
    void extractsTransactionsFromCsvContent() {
      var content =
          csvContent(
              "Transaction Date,Transaction Description,Transaction Amount,Transaction Type",
              "01/15/25,Grocery Store,52.34,Debit");

      var transactions = matchedTransactions(extractor, content, "account-123");

      assertThat(transactions).hasSize(1);
      var tx = transactions.get(0);
      assertThat(tx.date()).isEqualTo(LocalDate.of(2025, 1, 15));
      assertThat(tx.description()).isEqualTo("Grocery Store");
      assertThat(tx.amount()).isEqualByComparingTo(new BigDecimal("52.34"));
      assertThat(tx.type()).isEqualTo(TransactionType.DEBIT);
      assertThat(tx.bankName()).isEqualTo("Test Bank");
      assertThat(tx.currencyIsoCode()).isEqualTo("USD");
      assertThat(tx.accountId()).isEqualTo("account-123");
    }

    @Test
    void parsesCreditTransactions() {
      var content =
          csvContent(
              "Transaction Date,Transaction Description,Transaction Amount,Transaction Type",
              "01/16/25,Payroll Deposit,2500.00,Credit");

      var transactions = matchedTransactions(extractor, content, null);

      assertThat(transactions).hasSize(1);
      var tx = transactions.get(0);
      assertThat(tx.type()).isEqualTo(TransactionType.CREDIT);
      assertThat(tx.amount()).isEqualByComparingTo(new BigDecimal("2500.00"));
    }

    @Test
    void stripsNonNumericCharactersFromAmount() {
      var content =
          csvContent(
              "Transaction Date,Transaction Description,Transaction Amount,Transaction Type",
              "01/15/25,Test,\"$1,234.56\",Debit");

      var transactions = matchedTransactions(extractor, content, null);

      assertThat(transactions.get(0).amount()).isEqualByComparingTo(new BigDecimal("1234.56"));
    }

    @Test
    void dateTimeFormatRetriesDateOnlyRowsBecauseSimplifiedFormatterIsDistinct() {
      var dateTimeExtractor =
          createExtractor(
              8L,
              12L,
              "Bangkok Bank - Export",
              "Bangkok Bank",
              "THB",
              new CsvColumnParserConfig(
                  "Date", "d MMM uuuu HH:mm", "Description", "Credit", "Debit", null, null));
      var content =
          csvContent(
              "Date,Description,Credit,Debit",
              "31 Dec 2025 10:37,Payment for Goods /Services,,379.00",
              "25 Dec 2025,Interest Credit,22.91,");

      var transactions = matchedTransactions(dateTimeExtractor, content, null);

      assertThat(transactions).hasSize(2);
      assertThat(transactions.get(0).date()).isEqualTo(LocalDate.of(2025, 12, 31));
      assertThat(transactions.get(1).date()).isEqualTo(LocalDate.of(2025, 12, 25));
      assertThat(transactions.get(1).type()).isEqualTo(TransactionType.CREDIT);
    }

    @Test
    void returnsCsvParsingFailureForInvalidDate() {
      var content =
          csvContent(
              "Transaction Date,Transaction Description,Transaction Amount,Transaction Type",
              "invalid-date,Test,10.00,Debit");

      assertThat(failedAttempt(extractor, content, null).getCode())
          .isEqualTo(BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    @Test
    void returnsDateTooOldFailureForDateBefore2000() {
      // Use a 4-digit year format to test the year 2000 validation
      var extractor4Digit =
          createExtractor(
              8L,
              12L,
              "Test Bank - Export",
              "Test Bank",
              "USD",
              new CsvColumnParserConfig(
                  "Transaction Date",
                  "MM/dd/yyyy",
                  "Transaction Description",
                  "Transaction Amount",
                  "Transaction Amount",
                  "Transaction Type",
                  null));
      var content =
          csvContent(
              "Transaction Date,Transaction Description,Transaction Amount,Transaction Type",
              "01/15/1999,Test,10.00,Debit");

      assertThat(failedAttempt(extractor4Digit, content, null).getCode())
          .isEqualTo(BudgetAnalyzerError.TRANSACTION_DATE_TOO_OLD.name());
    }

    @Test
    void returnsCsvParsingFailureForMissingRequiredValue() {
      var content =
          csvContent(
              "Transaction Date,Transaction Description,Transaction Amount,Transaction Type",
              "01/15/25,,10.00,Debit");

      assertThat(failedAttempt(extractor, content, null).getCode())
          .isEqualTo(BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    @Test
    void returnsCsvParsingFailureForInvalidTransactionType() {
      var content =
          csvContent(
              "Transaction Date,Transaction Description,Transaction Amount,Transaction Type",
              "01/15/25,Test,10.00,InvalidType");

      assertThat(failedAttempt(extractor, content, null).getCode())
          .isEqualTo(BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    @Test
    void returnsCsvParsingFailureForMissingAmount() {
      var content =
          csvContent(
              "Transaction Date,Transaction Description,Transaction Amount,Transaction Type",
              "01/15/25,Test,,Debit");

      assertThat(failedAttempt(extractor, content, null).getCode())
          .isEqualTo(BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }
  }

  @Nested
  class ImplicitTypeExtraction {

    private ConfigurableCsvStatementExtractor implicitTypeExtractor;

    @BeforeEach
    void setUp() {
      implicitTypeExtractor =
          createExtractor(
              9L,
              13L,
              "Implicit Type Bank - Export",
              "Implicit Type Bank",
              "USD",
              new CsvColumnParserConfig(
                  "Date", "MM/dd/uu", "Description", "Credit", "Debit", null, null));
    }

    @Test
    void detectsCreditWhenCreditColumnPopulated() {
      var content = csvContent("Date,Description,Credit,Debit", "01/15/25,Deposit,100.00,");

      var transactions = matchedTransactions(implicitTypeExtractor, content, null);

      assertThat(transactions.get(0).type()).isEqualTo(TransactionType.CREDIT);
    }

    @Test
    void detectsDebitWhenDebitColumnPopulated() {
      var content = csvContent("Date,Description,Credit,Debit", "01/15/25,Purchase,,50.00");

      var transactions = matchedTransactions(implicitTypeExtractor, content, null);

      assertThat(transactions.get(0).type()).isEqualTo(TransactionType.DEBIT);
    }

    @Test
    void defaultsToDebitWhenCreditColumnIsEmpty() {
      var content = csvContent("Date,Description,Credit,Debit", "01/15/25,Unknown,,25.00");

      var transactions = matchedTransactions(implicitTypeExtractor, content, null);

      assertThat(transactions.get(0).type()).isEqualTo(TransactionType.DEBIT);
    }
  }

  @Nested
  class GetHandlerKey {

    @Test
    void returnsGeneratedHandlerKey() {
      assertThat(extractor.getHandlerKey()).isEqualTo(HANDLER_KEY);
    }
  }

  private ParserAttempt attempt(
      ConfigurableCsvStatementExtractor configurableCsvStatementExtractor,
      byte[] content,
      String filename,
      String accountId) {
    return configurableCsvStatementExtractor.attempt(
        parserRevision(11L), content, filename, accountId);
  }

  private List<org.budgetanalyzer.transaction.service.dto.PreviewTransaction> matchedTransactions(
      ConfigurableCsvStatementExtractor configurableCsvStatementExtractor,
      byte[] content,
      String accountId) {
    var parserAttempt =
        configurableCsvStatementExtractor.attempt(
            parserRevision(11L), content, "test.csv", accountId);
    assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.MATCHED);
    return parserAttempt.transactions();
  }

  private BusinessException failedAttempt(
      ConfigurableCsvStatementExtractor configurableCsvStatementExtractor,
      byte[] content,
      String accountId) {
    var parserAttempt =
        configurableCsvStatementExtractor.attempt(
            parserRevision(11L), content, "test.csv", accountId);
    assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.FAILED);
    return parserAttempt.failure();
  }

  private ConfigurableCsvStatementExtractor createExtractor(
      Long statementFormatId,
      Long parserRevisionId,
      String displayName,
      String bankName,
      String defaultCurrencyIsoCode,
      CsvColumnParserConfig csvColumnParserConfig) {
    var statementFormat =
        StatementFormat.createCsvFormat(
            displayName, bankName, defaultCurrencyIsoCode, "usr_test123");
    ReflectionTestUtils.setField(statementFormat, "id", statementFormatId);
    var parserRevision = ParserRevision.createCsvColumnConfig(statementFormat, 1, "{}");
    ReflectionTestUtils.setField(parserRevision, "id", parserRevisionId);
    return new ConfigurableCsvStatementExtractor(
        statementFormat, parserRevision, csvColumnParserConfig, new OpenCsvParser());
  }

  private ParserRevision parserRevision(Long parserRevisionId) {
    var statementFormat =
        StatementFormat.createCsvFormat(
            "Parser Revision Owner", "Parser Revision Bank", "USD", "usr_test123");
    var parserRevision = ParserRevision.createCsvColumnConfig(statementFormat, 1, "{}");
    ReflectionTestUtils.setField(parserRevision, "id", parserRevisionId);
    return parserRevision;
  }

  private byte[] csvContent(String headers, String... rows) {
    return (headers + "\n" + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
  }
}
