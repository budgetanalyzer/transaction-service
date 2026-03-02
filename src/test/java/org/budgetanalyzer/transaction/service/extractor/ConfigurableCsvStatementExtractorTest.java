package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import org.budgetanalyzer.core.csv.CsvData;
import org.budgetanalyzer.core.csv.CsvParser;
import org.budgetanalyzer.core.csv.CsvRow;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.TransactionType;

@ExtendWith(MockitoExtension.class)
class ConfigurableCsvStatementExtractorTest {

  @Mock private CsvParser csvParser;

  private ConfigurableCsvStatementExtractor extractor;

  @BeforeEach
  void setUp() {
    var format = createTestFormat();
    extractor = new ConfigurableCsvStatementExtractor(format, csvParser);
  }

  private StatementFormat createTestFormat() {
    return StatementFormat.createCsvFormat(
        "test-bank",
        "Test Bank - Export",
        "Test Bank",
        "USD",
        "Transaction Date",
        "MM/dd/uu",
        "Transaction Description",
        "Transaction Amount",
        "Transaction Amount",
        "Transaction Type",
        null);
  }

  @Nested
  class CanHandle {

    @Test
    void returnsTrueForCsvFileWithMatchingHeaders() {
      var csvContent =
          "Transaction Date,Transaction Description,Transaction Amount,Transaction Type\n";
      var content = csvContent.getBytes(StandardCharsets.UTF_8);

      var result = extractor.canHandle(content, "test.csv");

      assertThat(result).isTrue();
    }

    @Test
    void returnsFalseForNonCsvFile() {
      var content = "some content".getBytes(StandardCharsets.UTF_8);

      var result = extractor.canHandle(content, "test.pdf");

      assertThat(result).isFalse();
    }

    @Test
    void returnsFalseForNullFilename() {
      var content = "some content".getBytes(StandardCharsets.UTF_8);

      var result = extractor.canHandle(content, null);

      assertThat(result).isFalse();
    }

    @Test
    void returnsFalseForMissingRequiredHeaders() {
      var csvContent = "Date,Description\n";
      var content = csvContent.getBytes(StandardCharsets.UTF_8);

      var result = extractor.canHandle(content, "test.csv");

      assertThat(result).isFalse();
    }

    @Test
    void returnsTrueWithExtraHeaders() {
      var csvContent =
          "Transaction Date,Transaction Description,Transaction Amount,"
              + "Transaction Type,Extra Column\n";
      var content = csvContent.getBytes(StandardCharsets.UTF_8);

      var result = extractor.canHandle(content, "test.csv");

      assertThat(result).isTrue();
    }
  }

  @Nested
  class Extract {

    @Test
    void extractsTransactionsFromCsvData() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  new CsvRow(
                      2,
                      Map.of(
                          "Transaction Date", "01/15/25",
                          "Transaction Description", "Grocery Store",
                          "Transaction Amount", "52.34",
                          "Transaction Type", "Debit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq("test-bank")))
          .thenReturn(csvData);

      var result = extractor.extract("dummy".getBytes(), "account-123");

      assertThat(result.transactions()).hasSize(1);
      var tx = result.transactions().get(0);
      assertThat(tx.date()).isEqualTo(LocalDate.of(2025, 1, 15));
      assertThat(tx.description()).isEqualTo("Grocery Store");
      assertThat(tx.amount()).isEqualByComparingTo(new BigDecimal("52.34"));
      assertThat(tx.type()).isEqualTo(TransactionType.DEBIT);
      assertThat(tx.bankName()).isEqualTo("Test Bank");
      assertThat(tx.currencyIsoCode()).isEqualTo("USD");
      assertThat(tx.accountId()).isEqualTo("account-123");
    }

    @Test
    void parsesCreditTransactions() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  new CsvRow(
                      2,
                      Map.of(
                          "Transaction Date", "01/16/25",
                          "Transaction Description", "Payroll Deposit",
                          "Transaction Amount", "2500.00",
                          "Transaction Type", "Credit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq("test-bank")))
          .thenReturn(csvData);

      var result = extractor.extract("dummy".getBytes(), null);

      assertThat(result.transactions()).hasSize(1);
      var tx = result.transactions().get(0);
      assertThat(tx.type()).isEqualTo(TransactionType.CREDIT);
      assertThat(tx.amount()).isEqualByComparingTo(new BigDecimal("2500.00"));
    }

    @Test
    void stripsNonNumericCharactersFromAmount() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  new CsvRow(
                      2,
                      Map.of(
                          "Transaction Date", "01/15/25",
                          "Transaction Description", "Test",
                          "Transaction Amount", "$1,234.56",
                          "Transaction Type", "Debit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq("test-bank")))
          .thenReturn(csvData);

      var result = extractor.extract("dummy".getBytes(), null);

      assertThat(result.transactions().get(0).amount())
          .isEqualByComparingTo(new BigDecimal("1234.56"));
    }

    @Test
    void throwsExceptionForInvalidDate() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  new CsvRow(
                      2,
                      Map.of(
                          "Transaction Date", "invalid-date",
                          "Transaction Description", "Test",
                          "Transaction Amount", "10.00",
                          "Transaction Type", "Debit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq("test-bank")))
          .thenReturn(csvData);

      assertThatThrownBy(() -> extractor.extract("dummy".getBytes(), null))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("Invalid date value");
    }

    @Test
    void throwsExceptionForDateBefore2000() throws Exception {
      // Use a 4-digit year format to test the year 2000 validation
      var format4Digit =
          StatementFormat.createCsvFormat(
              "test-bank-4digit",
              "Test Bank - Export",
              "Test Bank",
              "USD",
              "Transaction Date",
              "MM/dd/yyyy",
              "Transaction Description",
              "Transaction Amount",
              "Transaction Amount",
              "Transaction Type",
              null);
      var extractor4Digit = new ConfigurableCsvStatementExtractor(format4Digit, csvParser);

      var csvData =
          new CsvData(
              "test.csv",
              "test-bank-4digit",
              List.of(
                  "Transaction Date",
                  "Transaction Description",
                  "Transaction Amount",
                  "Transaction Type"),
              List.of(
                  new CsvRow(
                      2,
                      Map.of(
                          "Transaction Date", "01/15/1999",
                          "Transaction Description", "Test",
                          "Transaction Amount", "10.00",
                          "Transaction Type", "Debit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq("test-bank-4digit")))
          .thenReturn(csvData);

      assertThatThrownBy(() -> extractor4Digit.extract("dummy".getBytes(), null))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("prior to year 2000");
    }

    @Test
    void throwsExceptionForMissingRequiredColumn() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  new CsvRow(
                      2,
                      Map.of(
                          "Transaction Date", "01/15/25",
                          "Transaction Amount", "10.00",
                          "Transaction Type", "Debit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq("test-bank")))
          .thenReturn(csvData);

      assertThatThrownBy(() -> extractor.extract("dummy".getBytes(), null))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("Missing value for required column");
    }

    @Test
    void throwsExceptionForInvalidTransactionType() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  new CsvRow(
                      2,
                      Map.of(
                          "Transaction Date", "01/15/25",
                          "Transaction Description", "Test",
                          "Transaction Amount", "10.00",
                          "Transaction Type", "InvalidType"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq("test-bank")))
          .thenReturn(csvData);

      assertThatThrownBy(() -> extractor.extract("dummy".getBytes(), null))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("Invalid value for required column");
    }

    @Test
    void throwsExceptionForMissingAmount() throws Exception {
      var csvData =
          createCsvData(
              List.of(
                  new CsvRow(
                      2,
                      Map.of(
                          "Transaction Date", "01/15/25",
                          "Transaction Description", "Test",
                          "Transaction Amount", "",
                          "Transaction Type", "Debit"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq("test-bank")))
          .thenReturn(csvData);

      assertThatThrownBy(() -> extractor.extract("dummy".getBytes(), null))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("Missing amount value");
    }
  }

  @Nested
  class ImplicitTypeExtraction {

    private ConfigurableCsvStatementExtractor implicitTypeExtractor;

    @BeforeEach
    void setUp() {
      var format =
          StatementFormat.createCsvFormat(
              "implicit-type-bank",
              "Implicit Type Bank - Export",
              "Implicit Type Bank",
              "USD",
              "Date",
              "MM/dd/uu",
              "Description",
              "Credit",
              "Debit",
              null,
              null);
      implicitTypeExtractor = new ConfigurableCsvStatementExtractor(format, csvParser);
    }

    @Test
    void detectsCreditWhenCreditColumnPopulated() throws Exception {
      var csvData =
          new CsvData(
              "test.csv",
              "implicit-type-bank",
              List.of("Date", "Description", "Credit", "Debit"),
              List.of(
                  new CsvRow(
                      2,
                      Map.of(
                          "Date", "01/15/25",
                          "Description", "Deposit",
                          "Credit", "100.00",
                          "Debit", ""))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq("implicit-type-bank")))
          .thenReturn(csvData);

      var result = implicitTypeExtractor.extract("dummy".getBytes(), null);

      assertThat(result.transactions().get(0).type()).isEqualTo(TransactionType.CREDIT);
    }

    @Test
    void detectsDebitWhenDebitColumnPopulated() throws Exception {
      var csvData =
          new CsvData(
              "test.csv",
              "implicit-type-bank",
              List.of("Date", "Description", "Credit", "Debit"),
              List.of(
                  new CsvRow(
                      2,
                      Map.of(
                          "Date", "01/15/25",
                          "Description", "Purchase",
                          "Credit", "",
                          "Debit", "50.00"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq("implicit-type-bank")))
          .thenReturn(csvData);

      var result = implicitTypeExtractor.extract("dummy".getBytes(), null);

      assertThat(result.transactions().get(0).type()).isEqualTo(TransactionType.DEBIT);
    }

    @Test
    void defaultsToDebitWhenBothColumnsEmpty() throws Exception {
      var csvData =
          new CsvData(
              "test.csv",
              "implicit-type-bank",
              List.of("Date", "Description", "Credit", "Debit"),
              List.of(
                  new CsvRow(
                      2,
                      Map.of(
                          "Date", "01/15/25",
                          "Description", "Unknown",
                          "Credit", "",
                          "Debit", "25.00"))));
      when(csvParser.parseCsvInputStream(any(InputStream.class), any(), eq("implicit-type-bank")))
          .thenReturn(csvData);

      var result = implicitTypeExtractor.extract("dummy".getBytes(), null);

      assertThat(result.transactions().get(0).type()).isEqualTo(TransactionType.DEBIT);
    }
  }

  @Nested
  class GetFormatKey {

    @Test
    void returnsConfiguredFormatKey() {
      assertThat(extractor.getFormatKey()).isEqualTo("test-bank");
    }
  }

  private CsvData createCsvData(List<CsvRow> rows) {
    return new CsvData(
        "test.csv",
        "test-bank",
        List.of(
            "Transaction Date",
            "Transaction Description",
            "Transaction Amount",
            "Transaction Type"),
        rows);
  }
}
