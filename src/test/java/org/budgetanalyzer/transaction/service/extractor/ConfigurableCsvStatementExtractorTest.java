package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.budgetanalyzer.core.csv.CsvData;
import org.budgetanalyzer.core.csv.CsvParser;
import org.budgetanalyzer.core.csv.CsvRow;
import org.budgetanalyzer.core.csv.impl.OpenCsvParser;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.CsvColumnParserConfig;
import org.budgetanalyzer.transaction.service.dto.ParserAttempt;
import org.budgetanalyzer.transaction.service.dto.ParserAttemptStatus;

@ExtendWith(MockitoExtension.class)
class ConfigurableCsvStatementExtractorTest {

  private static final String HANDLER_KEY = "statement-format-7-revision-11";
  private static final String HANDLER_KEY_4_DIGIT = "statement-format-8-revision-12";
  private static final String IMPLICIT_HANDLER_KEY = "statement-format-9-revision-13";

  @Mock private CsvParser csvParser;

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
      var realParserExtractor = createExtractorWithSharedParser();

      var parserAttempt = attempt(realParserExtractor, content, "test.csv", "account-123");

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
      var realParserExtractor = createExtractorWithSharedParser();

      var parserAttempt = attempt(realParserExtractor, content, "test.csv", null);

      assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.NOT_APPLICABLE);
    }

    @Test
    void returnsMatchedWithExtraHeaders() {
      var csvContent =
          "Transaction Date,Transaction Description,Transaction Amount,"
              + "Transaction Type,Extra Column\n"
              + "01/15/25,Grocery Store,52.34,Debit,ignored\n";
      var content = csvContent.getBytes(StandardCharsets.UTF_8);
      var realParserExtractor = createExtractorWithSharedParser();

      var parserAttempt = attempt(realParserExtractor, content, "test.csv", "account-123");

      assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.MATCHED);
    }
  }

  @Nested
  class SharedCsvParserHeaderBehavior {

    @Test
    void quotedHeadersAreAcceptedBySingleSharedParserAttempt() {
      var realParserExtractor = createExtractorWithSharedParser();
      var content =
          ("\"Transaction Date\",\"Transaction Description\",\"Transaction Amount\","
                  + "\"Transaction Type\"\n"
                  + "\"01/15/25\",\"Grocery Store\",\"52.34\",\"Debit\"\n")
              .getBytes(StandardCharsets.UTF_8);

      var transactions = matchedTransactions(realParserExtractor, content, "account-123");
      assertThat(transactions).hasSize(1);
      assertThat(transactions.getFirst().description()).isEqualTo("Grocery Store");
      assertThat(transactions.getFirst().amount()).isEqualByComparingTo(new BigDecimal("52.34"));
    }

    @Test
    void bomBearingHeadersAreNotApplicableAfterSingleSharedParserAttempt() {
      var realParserExtractor = createExtractorWithSharedParser();
      var content =
          ("\\uFEFFTransaction Date,Transaction Description,Transaction Amount,"
                  + "Transaction Type\n"
                  + "01/15/25,Grocery Store,52.34,Debit\n")
              .replace("\\uFEFF", "\uFEFF")
              .getBytes(StandardCharsets.UTF_8);

      var parserAttempt = attempt(realParserExtractor, content, "bom.csv", "account-123");

      assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.NOT_APPLICABLE);
    }

    @Test
    void extraHeadersAreAcceptedAndSharedParserExtractionIgnoresExtraValues() {
      var realParserExtractor = createExtractorWithSharedParser();
      var content =
          ("Transaction Date,Transaction Description,Transaction Amount,Transaction Type,Extra\n"
                  + "01/15/25,Grocery Store,52.34,Debit,ignored\n")
              .getBytes(StandardCharsets.UTF_8);

      var transactions = matchedTransactions(realParserExtractor, content, "account-123");
      assertThat(transactions).hasSize(1);
      assertThat(transactions.getFirst().date()).isEqualTo(LocalDate.of(2025, 1, 15));
      assertThat(transactions.getFirst().description()).isEqualTo("Grocery Store");
      assertThat(transactions.getFirst().type()).isEqualTo(TransactionType.DEBIT);
    }
  }

  @Nested
  class Extract {

    @Test
    void extractsTransactionsFromCsvData() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  CsvRow.of(
                      2,
                      Map.of(
                          "Transaction Date", "01/15/25",
                          "Transaction Description", "Grocery Store",
                          "Transaction Amount", "52.34",
                          "Transaction Type", "Debit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq(HANDLER_KEY)))
          .thenReturn(csvData);

      var transactions = matchedTransactions(extractor, "dummy".getBytes(), "account-123");

      assertThat(transactions).hasSize(1);
      var tx = transactions.get(0);
      assertThat(tx.date()).isEqualTo(LocalDate.of(2025, 1, 15));
      assertThat(tx.description()).isEqualTo("Grocery Store");
      assertThat(tx.amount()).isEqualByComparingTo(new BigDecimal("52.34"));
      assertThat(tx.type()).isEqualTo(TransactionType.DEBIT);
      assertThat(tx.bankName()).isEqualTo("Test Bank");
      assertThat(tx.currencyIsoCode()).isEqualTo("USD");
      assertThat(tx.accountId()).isEqualTo("account-123");
      verify(csvParser, times(1))
          .parseCsvInputStream(any(InputStream.class), any(), eq(HANDLER_KEY));
    }

    @Test
    void parsesCreditTransactions() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  CsvRow.of(
                      2,
                      Map.of(
                          "Transaction Date", "01/16/25",
                          "Transaction Description", "Payroll Deposit",
                          "Transaction Amount", "2500.00",
                          "Transaction Type", "Credit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq(HANDLER_KEY)))
          .thenReturn(csvData);

      var transactions = matchedTransactions(extractor, "dummy".getBytes(), null);

      assertThat(transactions).hasSize(1);
      var tx = transactions.get(0);
      assertThat(tx.type()).isEqualTo(TransactionType.CREDIT);
      assertThat(tx.amount()).isEqualByComparingTo(new BigDecimal("2500.00"));
    }

    @Test
    void stripsNonNumericCharactersFromAmount() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  CsvRow.of(
                      2,
                      Map.of(
                          "Transaction Date", "01/15/25",
                          "Transaction Description", "Test",
                          "Transaction Amount", "$1,234.56",
                          "Transaction Type", "Debit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq(HANDLER_KEY)))
          .thenReturn(csvData);

      var transactions = matchedTransactions(extractor, "dummy".getBytes(), null);

      assertThat(transactions.get(0).amount()).isEqualByComparingTo(new BigDecimal("1234.56"));
    }

    @Test
    void dateTimeFormatRetriesDateOnlyRowsBecauseSimplifiedFormatterIsDistinct() throws Exception {
      var dateTimeExtractor =
          createExtractor(
              8L,
              12L,
              "Bangkok Bank - Export",
              "Bangkok Bank",
              "THB",
              new CsvColumnParserConfig(
                  "Date", "d MMM uuuu HH:mm", "Description", "Credit", "Debit", null, null));
      var csvData =
          new CsvData(
              "test.csv",
              HANDLER_KEY_4_DIGIT,
              List.of("Date", "Description", "Credit", "Debit"),
              List.of(
                  CsvRow.of(
                      2,
                      Map.of(
                          "Date", "31 Dec 2025 10:37",
                          "Description", "Payment for Goods /Services",
                          "Credit", "",
                          "Debit", "379.00")),
                  CsvRow.of(
                      3,
                      Map.of(
                          "Date", "25 Dec 2025",
                          "Description", "Interest Credit",
                          "Credit", "22.91",
                          "Debit", ""))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq(HANDLER_KEY_4_DIGIT)))
          .thenReturn(csvData);

      var transactions = matchedTransactions(dateTimeExtractor, "dummy".getBytes(), null);

      assertThat(transactions).hasSize(2);
      assertThat(transactions.get(0).date()).isEqualTo(LocalDate.of(2025, 12, 31));
      assertThat(transactions.get(1).date()).isEqualTo(LocalDate.of(2025, 12, 25));
      assertThat(transactions.get(1).type()).isEqualTo(TransactionType.CREDIT);
    }

    @Test
    void throwsExceptionForInvalidDate() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  CsvRow.of(
                      2,
                      Map.of(
                          "Transaction Date", "invalid-date",
                          "Transaction Description", "Test",
                          "Transaction Amount", "10.00",
                          "Transaction Type", "Debit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq(HANDLER_KEY)))
          .thenReturn(csvData);

      assertThat(failedAttempt(extractor, "dummy".getBytes(), null).getMessage())
          .contains("Invalid date value");
    }

    @Test
    void throwsExceptionForDateBefore2000() throws Exception {
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

      var csvData =
          new CsvData(
              "test.csv",
              HANDLER_KEY_4_DIGIT,
              List.of(
                  "Transaction Date",
                  "Transaction Description",
                  "Transaction Amount",
                  "Transaction Type"),
              List.of(
                  CsvRow.of(
                      2,
                      Map.of(
                          "Transaction Date", "01/15/1999",
                          "Transaction Description", "Test",
                          "Transaction Amount", "10.00",
                          "Transaction Type", "Debit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq(HANDLER_KEY_4_DIGIT)))
          .thenReturn(csvData);

      assertThat(failedAttempt(extractor4Digit, "dummy".getBytes(), null).getMessage())
          .contains("prior to year 2000");
    }

    @Test
    void throwsExceptionForMissingRequiredColumn() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  CsvRow.of(
                      2,
                      Map.of(
                          "Transaction Date", "01/15/25",
                          "Transaction Amount", "10.00",
                          "Transaction Type", "Debit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq(HANDLER_KEY)))
          .thenReturn(csvData);

      assertThat(failedAttempt(extractor, "dummy".getBytes(), null).getMessage())
          .contains("Missing value for required column");
    }

    @Test
    void throwsExceptionForInvalidTransactionType() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  CsvRow.of(
                      2,
                      Map.of(
                          "Transaction Date", "01/15/25",
                          "Transaction Description", "Test",
                          "Transaction Amount", "10.00",
                          "Transaction Type", "InvalidType"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq(HANDLER_KEY)))
          .thenReturn(csvData);

      assertThat(failedAttempt(extractor, "dummy".getBytes(), null).getMessage())
          .contains("Invalid value for required column");
    }

    @Test
    void throwsExceptionForMissingAmount() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  CsvRow.of(
                      2,
                      Map.of(
                          "Transaction Date", "01/15/25",
                          "Transaction Description", "Test",
                          "Transaction Amount", "",
                          "Transaction Type", "Debit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq(HANDLER_KEY)))
          .thenReturn(csvData);

      assertThat(failedAttempt(extractor, "dummy".getBytes(), null).getMessage())
          .contains("Missing amount value");
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
    void detectsCreditWhenCreditColumnPopulated() throws Exception {
      var csvData =
          new CsvData(
              "test.csv",
              IMPLICIT_HANDLER_KEY,
              List.of("Date", "Description", "Credit", "Debit"),
              List.of(
                  CsvRow.of(
                      2,
                      Map.of(
                          "Date", "01/15/25",
                          "Description", "Deposit",
                          "Credit", "100.00",
                          "Debit", ""))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq(IMPLICIT_HANDLER_KEY)))
          .thenReturn(csvData);

      var transactions = matchedTransactions(implicitTypeExtractor, "dummy".getBytes(), null);

      assertThat(transactions.get(0).type()).isEqualTo(TransactionType.CREDIT);
    }

    @Test
    void detectsDebitWhenDebitColumnPopulated() throws Exception {
      var csvData =
          new CsvData(
              "test.csv",
              IMPLICIT_HANDLER_KEY,
              List.of("Date", "Description", "Credit", "Debit"),
              List.of(
                  CsvRow.of(
                      2,
                      Map.of(
                          "Date", "01/15/25",
                          "Description", "Purchase",
                          "Credit", "",
                          "Debit", "50.00"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq(IMPLICIT_HANDLER_KEY)))
          .thenReturn(csvData);

      var transactions = matchedTransactions(implicitTypeExtractor, "dummy".getBytes(), null);

      assertThat(transactions.get(0).type()).isEqualTo(TransactionType.DEBIT);
    }

    @Test
    void defaultsToDebitWhenBothColumnsEmpty() throws Exception {
      var csvData =
          new CsvData(
              "test.csv",
              IMPLICIT_HANDLER_KEY,
              List.of("Date", "Description", "Credit", "Debit"),
              List.of(
                  CsvRow.of(
                      2,
                      Map.of(
                          "Date", "01/15/25",
                          "Description", "Unknown",
                          "Credit", "",
                          "Debit", "25.00"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq(IMPLICIT_HANDLER_KEY)))
          .thenReturn(csvData);

      var transactions = matchedTransactions(implicitTypeExtractor, "dummy".getBytes(), null);

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

  private CsvData createCsvData(List<CsvRow> rows) {
    return new CsvData(
        "test.csv",
        HANDLER_KEY,
        List.of(
            "Transaction Date",
            "Transaction Description",
            "Transaction Amount",
            "Transaction Type"),
        rows);
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

  private org.budgetanalyzer.service.exception.BusinessException failedAttempt(
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
        statementFormat, parserRevision, csvColumnParserConfig, csvParser);
  }

  private ConfigurableCsvStatementExtractor createExtractorWithSharedParser() {
    return new ConfigurableCsvStatementExtractor(
        extractorFormat(7L, "Test Bank - Export", "Test Bank", "USD"),
        parserRevision(11L),
        new CsvColumnParserConfig(
            "Transaction Date",
            "MM/dd/uu",
            "Transaction Description",
            "Transaction Amount",
            "Transaction Amount",
            "Transaction Type",
            null),
        new OpenCsvParser());
  }

  private StatementFormat extractorFormat(
      Long statementFormatId, String displayName, String bankName, String defaultCurrencyIsoCode) {
    var statementFormat =
        StatementFormat.createCsvFormat(
            displayName, bankName, defaultCurrencyIsoCode, "usr_test123");
    ReflectionTestUtils.setField(statementFormat, "id", statementFormatId);
    return statementFormat;
  }

  private ParserRevision parserRevision(Long parserRevisionId) {
    var statementFormat =
        StatementFormat.createCsvFormat(
            "Parser Revision Owner", "Parser Revision Bank", "USD", "usr_test123");
    var parserRevision = ParserRevision.createCsvColumnConfig(statementFormat, 1, "{}");
    ReflectionTestUtils.setField(parserRevision, "id", parserRevisionId);
    return parserRevision;
  }
}
