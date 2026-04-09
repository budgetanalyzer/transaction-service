package org.budgetanalyzer.transaction.service.extractor;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.budgetanalyzer.core.csv.CsvParser;
import org.budgetanalyzer.core.csv.CsvRow;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

/**
 * A configurable CSV statement extractor that uses database-driven format configuration.
 *
 * <p>Instances are created dynamically from StatementFormat entities by the
 * StatementExtractorRegistry. Not a Spring component - managed by the registry.
 */
public class ConfigurableCsvStatementExtractor implements StatementExtractor {

  private static final Logger log =
      LoggerFactory.getLogger(ConfigurableCsvStatementExtractor.class);

  private static final Map<String, TransactionType> TRANSACTION_TYPE_MAP =
      initializeTransactionTypeMap();

  private final StatementFormat format;
  private final CsvParser csvParser;
  private final Map<String, DateTimeFormatter> dateFormatterCache = new HashMap<>();

  /**
   * Constructs a new ConfigurableCsvStatementExtractor.
   *
   * @param format the statement format configuration from the database
   * @param csvParser the CSV parser to use
   */
  public ConfigurableCsvStatementExtractor(StatementFormat format, CsvParser csvParser) {
    this.format = format;
    this.csvParser = csvParser;
    // Pre-cache the primary date formatter
    this.dateFormatterCache.put(format.getDateFormat(), buildDateFormatter(format.getDateFormat()));
  }

  private static Map<String, TransactionType> initializeTransactionTypeMap() {
    var rv = new HashMap<String, TransactionType>();
    addTransactionType(rv, TransactionType.CREDIT, "credit", "deposit");
    addTransactionType(rv, TransactionType.DEBIT, "debit", "withdrawal");
    return Map.copyOf(rv);
  }

  private static void addTransactionType(
      Map<String, TransactionType> map, TransactionType type, String... aliases) {
    for (String alias : aliases) {
      map.put(alias.toLowerCase(Locale.ROOT), type);
    }
  }

  @Override
  public boolean canHandle(byte[] fileContent, String filename) {
    if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
      return false;
    }
    return validateHeaders(fileContent);
  }

  @Override
  public ExtractionResult extract(byte[] fileContent, String accountId) {
    try {
      var csvData =
          csvParser.parseCsvInputStream(
              new ByteArrayInputStream(fileContent), "preview.csv", format.getFormatKey());

      var transactions = csvData.rows().stream().map(row -> mapToPreview(row, accountId)).toList();

      return new ExtractionResult(transactions, Collections.emptyList());
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(
          "Failed to parse CSV file: " + e.getMessage(),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name(),
          e);
    }
  }

  @Override
  public List<Transaction> extractEntities(
      byte[] fileContent, String accountId, FileImport fileImport) {
    try {
      var csvData =
          csvParser.parseCsvInputStream(
              new ByteArrayInputStream(fileContent),
              fileImport.getOriginalFilename(),
              format.getFormatKey());

      return csvData.rows().stream().map(row -> mapToEntity(row, accountId, fileImport)).toList();
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(
          "Failed to parse CSV file: " + e.getMessage(),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name(),
          e);
    }
  }

  @Override
  public String getFormatKey() {
    return format.getFormatKey();
  }

  private boolean validateHeaders(byte[] content) {
    try {
      var text = new String(content, StandardCharsets.UTF_8);
      var lines = text.split("\\r?\\n");
      if (lines.length == 0) {
        return false;
      }

      var headerLine = lines[0];
      var headers = Set.of(headerLine.split(","));

      // Check required headers exist
      var requiredHeaders =
          List.of(format.getDateHeader(), format.getDescriptionHeader(), format.getCreditHeader());

      for (var header : requiredHeaders) {
        if (header != null && !containsHeader(headers, header)) {
          log.debug(
              "Format '{}' header validation failed: missing '{}' in headers: {}",
              format.getFormatKey(),
              header,
              headers);
          return false;
        }
      }

      return true;
    } catch (Exception e) {
      log.debug(
          "Header validation failed for format '{}': {}", format.getFormatKey(), e.getMessage());
      return false;
    }
  }

  private boolean containsHeader(Set<String> headers, String expected) {
    return headers.stream().map(String::trim).anyMatch(h -> h.equalsIgnoreCase(expected));
  }

  private PreviewTransaction mapToPreview(CsvRow csvRow, String accountId) {
    var context = new CsvFileContext(csvRow);

    var date = parseDate(context);
    validateDateNotBeforeYear2000(date, context);
    validateDateNotTooFarInFuture(date, context);

    var description = getRequiredValue(context, format.getDescriptionHeader());
    var typeAndAmount = parseTypeAndAmount(context);
    var category = extractCategory(context);

    return new PreviewTransaction(
        date,
        description,
        typeAndAmount.amount(),
        typeAndAmount.type(),
        category,
        format.getBankName(),
        format.getDefaultCurrencyIsoCode(),
        accountId);
  }

  private Transaction mapToEntity(CsvRow csvRow, String accountId, FileImport fileImport) {
    var context = new CsvFileContext(csvRow);

    var date = parseDate(context);
    validateDateNotBeforeYear2000(date, context);
    validateDateNotTooFarInFuture(date, context);

    var description = getRequiredValue(context, format.getDescriptionHeader());
    var typeAndAmount = parseTypeAndAmount(context);

    var transaction = new Transaction();
    transaction.setAccountId(accountId);
    transaction.setBankName(format.getBankName());
    transaction.setDescription(description);
    transaction.setDate(date);
    transaction.setCurrencyIsoCode(format.getDefaultCurrencyIsoCode());
    transaction.setType(typeAndAmount.type());
    transaction.setAmount(typeAndAmount.amount());
    transaction.setFileImport(fileImport);

    return transaction;
  }

  private record TypeAndAmount(TransactionType type, BigDecimal amount) {}

  private TypeAndAmount parseTypeAndAmount(CsvFileContext context) {
    if (format.getTypeHeader() != null) {
      // Explicit type column with values like 'Debit' 'Credit'
      var type = parseTransactionType(context);
      var columnName =
          (type == TransactionType.CREDIT) ? format.getCreditHeader() : format.getDebitHeader();
      var amount = parseAmount(context, context.row().values().get(columnName));
      return new TypeAndAmount(type, amount);
    } else {
      // Implicit type - determined by which column is populated
      var row = context.row().values();
      var debitVal = row.get(format.getDebitHeader());
      var creditVal = row.get(format.getCreditHeader());

      TransactionType type;
      String amountColumnName;

      if (isBlank(debitVal) && !isBlank(creditVal)) {
        type = TransactionType.CREDIT;
        amountColumnName = format.getCreditHeader();
      } else {
        type = TransactionType.DEBIT;
        amountColumnName = format.getDebitHeader();
      }

      var amount = parseAmount(context, row.get(amountColumnName));
      return new TypeAndAmount(type, amount);
    }
  }

  private String extractCategory(CsvFileContext context) {
    if (format.getCategoryHeader() == null) {
      return null;
    }
    return context.row().values().get(format.getCategoryHeader());
  }

  private LocalDate parseDate(CsvFileContext context) {
    var rawDate = getRequiredValue(context, format.getDateHeader());
    var dateFormat = format.getDateFormat();
    var formatter = dateFormatterCache.get(dateFormat);

    try {
      return LocalDate.from(formatter.parse(rawDate));
    } catch (DateTimeParseException d) {
      try {
        return parseWithSimplifiedFormat(dateFormat, rawDate);
      } catch (Exception e) {
        throw new BusinessException(
            String.format(
                "Invalid date value '%s' at line %d", rawDate, context.row().lineNumber()),
            BudgetAnalyzerError.CSV_PARSING_ERROR.name());
      }
    }
  }

  private LocalDate parseWithSimplifiedFormat(String dateFormat, String rawDate) {
    var simpleFormatter = getSimpleFormatter(dateFormat);
    return LocalDate.from(simpleFormatter.parse(rawDate));
  }

  private DateTimeFormatter getSimpleFormatter(String dateFormat) {
    var simplifiedPattern = dateFormat.replaceAll("\\s*HH(:mm(:ss)?)?", "").trim();
    var simpleFormatter = dateFormatterCache.get(simplifiedPattern);

    if (simpleFormatter == null) {
      simpleFormatter = buildDateFormatter(simplifiedPattern);
      dateFormatterCache.put(simplifiedPattern, simpleFormatter);
    }

    return simpleFormatter;
  }

  private DateTimeFormatter buildDateFormatter(String dateFormat) {
    return DateTimeFormatter.ofPattern(dateFormat, Locale.ROOT)
        .withResolverStyle(ResolverStyle.SMART);
  }

  private void validateDateNotBeforeYear2000(LocalDate date, CsvFileContext context) {
    if (date.getYear() < 2000) {
      throw new BusinessException(
          String.format(
              "Transaction date '%s' at line %d is prior to year 2000. "
                  + "Transactions before 2000 are not supported due to EUR exchange rate "
                  + "limitations and 2-digit year format ambiguity.",
              date, context.row().lineNumber()),
          BudgetAnalyzerError.TRANSACTION_DATE_TOO_OLD.name());
    }
  }

  private void validateDateNotTooFarInFuture(LocalDate date, CsvFileContext context) {
    var today = LocalDate.now();
    var maxAllowedDate = today.plusDays(1);

    if (date.isAfter(maxAllowedDate)) {
      throw new BusinessException(
          String.format(
              "Transaction date '%s' at line %d is more than 1 day in the future. "
                  + "Future-dated transactions are not allowed to prevent data entry errors.",
              date, context.row().lineNumber()),
          BudgetAnalyzerError.TRANSACTION_DATE_TOO_FAR_IN_FUTURE.name());
    }
  }

  private TransactionType parseTransactionType(CsvFileContext context) {
    var rawType = getRequiredValue(context, format.getTypeHeader());
    var type = TRANSACTION_TYPE_MAP.get(rawType.trim().toLowerCase(Locale.ROOT));

    if (type == null) {
      throw new BusinessException(
          String.format(
              "Invalid value for required column '%s' at line %d",
              format.getTypeHeader(), context.row().lineNumber()),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    return type;
  }

  private BigDecimal parseAmount(CsvFileContext context, String rawAmount) {
    if (isBlank(rawAmount)) {
      throw new BusinessException(
          String.format("Missing amount value at line %d", context.row().lineNumber()),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    try {
      var cleaned = rawAmount.replaceAll("[^0-9.]", "");
      return new BigDecimal(cleaned);
    } catch (Exception e) {
      throw new BusinessException(
          String.format(
              "Invalid amount value '%s' at line %d", rawAmount, context.row().lineNumber()),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }
  }

  private String getRequiredValue(CsvFileContext context, String columnName) {
    var val = context.row().values().get(columnName);

    if (isBlank(val)) {
      throw new BusinessException(
          String.format(
              "Missing value for required column '%s' at line %d",
              columnName, context.row().lineNumber()),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    return val;
  }

  private boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private record CsvFileContext(CsvRow row) {}
}
