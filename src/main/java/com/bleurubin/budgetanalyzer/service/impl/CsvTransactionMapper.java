package com.bleurubin.budgetanalyzer.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bleurubin.budgetanalyzer.config.CsvConfig;
import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.domain.TransactionType;
import com.bleurubin.budgetanalyzer.service.BudgetAnalyzerError;
import com.bleurubin.core.domain.CsvRow;
import com.bleurubin.core.util.JsonUtils;
import com.bleurubin.service.exception.BusinessException;

// package private class- this is just an implementation detail and shouldn't be
// used outside of the service.impl package
class CsvTransactionMapper {

  private static final Logger log = LoggerFactory.getLogger(CsvTransactionMapper.class);
  private static final Map<String, TransactionType> TRANSACTION_TYPE_MAP =
      initializeTransactionTypeMap();

  private static Map<String, TransactionType> initializeTransactionTypeMap() {
    var rv = new HashMap<String, TransactionType>();
    addTransactionType(rv, TransactionType.CREDIT, "credit", "deposit");
    addTransactionType(rv, TransactionType.DEBIT, "debit", "withdrawal");

    return Map.copyOf(rv); // Make it immutable
  }

  private final Map<String, CsvConfig> csvConfigMap;
  private final Map<String, DateTimeFormatter> dateFormatterMap;

  public CsvTransactionMapper(Map<String, CsvConfig> csvConfigMap) {
    this.csvConfigMap = csvConfigMap;

    // cache date formats so we don't create a new DateFormatter for every row
    this.dateFormatterMap =
        csvConfigMap.values().stream()
            .collect(
                Collectors.toMap(
                    CsvConfig::dateFormat,
                    c -> buildDateFormatter(c.dateFormat()),
                    (existing, replacement) -> existing));
  }

  private static void addTransactionType(
      Map<String, TransactionType> map, TransactionType type, String... aliases) {
    for (String alias : aliases) {
      map.put(alias.toLowerCase(Locale.ROOT), type);
    }
  }

  public Transaction map(String fileName, String format, String accountId, CsvRow csvRow) {
    var csvConfig = getConfig(format);
    var fileContext = new CsvFileContext(fileName, format, csvRow);
    log.debug("Processing fileContext: {}", JsonUtils.toJson(fileContext));

    var rv = buildTransaction(csvConfig, fileContext, accountId);
    mapTypeAndAmount(rv, csvConfig, fileContext);

    return rv;
  }

  private Transaction buildTransaction(
      CsvConfig csvConfig, CsvFileContext fileContext, String accountId) {
    var rv = new Transaction();
    rv.setAccountId(accountId);
    rv.setBankName(csvConfig.bankName());
    rv.setDescription(getRequiredValue(fileContext, csvConfig.descriptionHeader()));
    rv.setDate(parseDate(csvConfig, fileContext));

    // if we need to support multiple currencies for a given bank we can pass
    // it through with accountId. just use the default for now
    rv.setCurrencyIsoCode(csvConfig.defaultCurrencyIsoCode());

    return rv;
  }

  /*
   * Some csv files show a single amount column and a type column to indicate DEBIT
   * or CREDIT, others have separate columns for each and the type is implicit.
   */
  private void mapTypeAndAmount(
      Transaction transaction, CsvConfig csvConfig, CsvFileContext fileContext) {
    if (csvConfig.typeHeader() != null) {
      handleTypeFromColumn(transaction, csvConfig, fileContext);
    } else {
      handleImplicitType(transaction, csvConfig, fileContext);
    }
  }

  // There is an explicit type column with values like 'Debit' 'Credit'
  private void handleTypeFromColumn(
      Transaction transaction, CsvConfig csvConfig, CsvFileContext fileContext) {
    var row = fileContext.rowMap();
    var type = parseTransactionType(csvConfig, fileContext);
    transaction.setType(type);

    var columnName =
        (type == TransactionType.CREDIT) ? csvConfig.creditHeader() : csvConfig.debitHeader();
    transaction.setAmount(parseAmount(row.get(columnName)));
  }

  // There is no explicit type column, we get the type by determining which
  // column (i.e. 'Debit' or 'Credit') is populated
  private void handleImplicitType(
      Transaction transaction, CsvConfig csvConfig, CsvFileContext fileContext) {
    var row = fileContext.rowMap();
    var debitVal = row.get(csvConfig.debitHeader());
    var creditVal = row.get(csvConfig.creditHeader());

    TransactionType type;
    String amountColumnName;

    if (isBlank(debitVal) && !isBlank(creditVal)) {
      type = TransactionType.CREDIT;
      amountColumnName = csvConfig.creditHeader();
    } else {
      type = TransactionType.DEBIT;
      amountColumnName = csvConfig.debitHeader();
    }

    transaction.setType(type);
    transaction.setAmount(parseAmount(row.get(amountColumnName)));
  }

  private BigDecimal parseAmount(String raw) {
    if (isBlank(raw)) {
      throw new BusinessException(
          "Missing transaction amount value", BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    var cleaned = raw.replaceAll("[^\\d.-]", "");
    return new BigDecimal(cleaned);
  }

  /*
   * Find a cached formatter for the given dateFormat.  If it fails, try stripping
   * the time fields from the rawDate input.  If that succeeds, lazily cache the
   * date only pattern
   */
  private LocalDate parseDate(CsvConfig csvConfig, CsvFileContext fileContext) {
    var rawDate = getRequiredValue(fileContext, csvConfig.dateHeader());
    var dateFormat = csvConfig.dateFormat();
    var formatter = dateFormatterMap.get(dateFormat);

    try {
      return LocalDate.from(formatter.parse(rawDate));
    } catch (DateTimeParseException e) {
      return parseWithSimplifiedFormat(dateFormat, rawDate);
    }
  }

  private LocalDate parseWithSimplifiedFormat(String dateFormat, String rawDate) {
    var simpleFormatter = getSimpleFormatter(dateFormat);
    return LocalDate.from(simpleFormatter.parse(rawDate));
  }

  /*
   * Strip the time fields from the date format and try again.  Some banks
   * send both a LocalDate format and a LocalDateTime format depending on
   * the transaction.
   */
  private DateTimeFormatter getSimpleFormatter(String dateFormat) {
    // remove HH:mm or HH:mm:ss
    var simplifiedPattern = dateFormat.replaceAll("\\s*HH(:mm(:ss)?)?", "").trim();
    var simpleFormatter = dateFormatterMap.get(simplifiedPattern);

    if (simpleFormatter == null) {
      simpleFormatter = buildDateFormatter(simplifiedPattern);
      dateFormatterMap.put(simplifiedPattern, simpleFormatter);
    }

    return simpleFormatter;
  }

  private DateTimeFormatter buildDateFormatter(String dateFormat) {
    return DateTimeFormatter.ofPattern(dateFormat, Locale.ROOT)
        .withResolverStyle(ResolverStyle.SMART);
  }

  private TransactionType parseTransactionType(CsvConfig csvConfig, CsvFileContext fileContext) {
    var rawType = getRequiredValue(fileContext, csvConfig.typeHeader());
    var type = TRANSACTION_TYPE_MAP.get(rawType.trim().toLowerCase(Locale.ROOT));

    if (type == null) {
      throw new BusinessException(
          String.format(
              "Invalid value for required column '%s' at line %d in file '%s'",
              csvConfig.typeHeader(), fileContext.lineNumber(), fileContext.fileName()),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    return type;
  }

  private String getRequiredValue(CsvFileContext fileContext, String columnName) {
    var val = fileContext.rowMap().get(columnName);

    if (isBlank(val)) {
      throw new BusinessException(
          String.format(
              "Missing value for required column '%s' at line %d in file '%s'",
              columnName, fileContext.lineNumber(), fileContext.fileName()),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    return val;
  }

  private CsvConfig getConfig(String format) {
    var rv = csvConfigMap.get(format);
    if (rv == null) {
      throw new BusinessException(
          "No csvConfig found for format: " + format,
          BudgetAnalyzerError.CSV_FORMAT_NOT_SUPPORTED.name());
    }

    return rv;
  }

  private boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private record CsvFileContext(String fileName, String format, CsvRow csvRow) {
    int lineNumber() {
      return csvRow.lineNumber();
    }

    Map<String, String> rowMap() {
      return csvRow.values();
    }
  }
}
