package org.budgetanalyzer.transaction.service;

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

import org.budgetanalyzer.core.csv.CsvRow;
import org.budgetanalyzer.core.logging.SafeLogger;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.config.CsvConfig;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;

/**
 * Mapper for converting CSV rows to Transaction domain entities.
 *
 * <p>Package-private class - implementation detail that shouldn't be used outside of the service
 * package. Tracks file context for better error messages (file/line number/value/error). Philosophy
 * is to throw error messages as close to parsing errors as possible to get exact error/line number
 * info.
 */
class CsvTransactionMapper {

  private static final Logger log = LoggerFactory.getLogger(CsvTransactionMapper.class);
  private static final Map<String, TransactionType> TRANSACTION_TYPE_MAP =
      initializeTransactionTypeMap();

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

  private static Map<String, TransactionType> initializeTransactionTypeMap() {
    var rv = new HashMap<String, TransactionType>();
    addTransactionType(rv, TransactionType.CREDIT, "credit", "deposit");
    addTransactionType(rv, TransactionType.DEBIT, "debit", "withdrawal");

    return Map.copyOf(rv); // Make it immutable
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
    log.debug("Processing fileContext: {}", SafeLogger.toJson(fileContext));

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

    var date = parseDate(csvConfig, fileContext);
    validateDateNotBeforeYear2000(date, fileContext);
    validateDateNotTooFarInFuture(date, fileContext);
    rv.setDate(date);

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
    transaction.setAmount(parseAmount(fileContext, row.get(columnName)));
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
    transaction.setAmount(parseAmount(fileContext, row.get(amountColumnName)));
  }

  private BigDecimal parseAmount(CsvFileContext fileContext, String rawAmount) {
    if (isBlank(rawAmount)) {
      throw new BusinessException(
          String.format(
              "Missing amount value at line %d in file '%s'",
              fileContext.lineNumber(), fileContext.fileName()),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    try {
      var cleaned = rawAmount.replaceAll("[^0-9.]", "");
      return new BigDecimal(cleaned);
    } catch (Exception e) {
      throw new BusinessException(
          String.format(
              "Invalid amount value '%s' at line %d in file '%s'",
              rawAmount, fileContext.lineNumber(), fileContext.fileName()),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }
  }

  /**
   * Validates that the transaction date is not prior to the year 2000.
   *
   * <p>Transactions before 2000 are not supported due to EUR exchange rate data limitations (only
   * available from 1999) and ambiguity in 2-digit year date formats.
   *
   * @param date The parsed transaction date
   * @param fileContext CSV file context for error reporting
   * @throws BusinessException if the date is before January 1, 2000
   */
  private void validateDateNotBeforeYear2000(LocalDate date, CsvFileContext fileContext) {
    if (date.getYear() < 2000) {
      throw new BusinessException(
          String.format(
              "Transaction date '%s' at line %d in file '%s' is prior to year 2000. "
                  + "Transactions before 2000 are not supported due to EUR exchange rate "
                  + "limitations and 2-digit year format ambiguity.",
              date, fileContext.lineNumber(), fileContext.fileName()),
          BudgetAnalyzerError.TRANSACTION_DATE_TOO_OLD.name());
    }
  }

  /**
   * Validates that the transaction date is not more than 1 day in the future.
   *
   * <p>Allows 1 day padding to account for timezone differences, but rejects dates further in the
   * future to prevent data entry errors.
   *
   * @param date The parsed transaction date
   * @param fileContext CSV file context for error reporting
   * @throws BusinessException if the date is more than 1 day in the future
   */
  private void validateDateNotTooFarInFuture(LocalDate date, CsvFileContext fileContext) {
    var today = LocalDate.now();
    var maxAllowedDate = today.plusDays(1);

    if (date.isAfter(maxAllowedDate)) {
      throw new BusinessException(
          String.format(
              "Transaction date '%s' at line %d in file '%s' is more than 1 day in the future. "
                  + "Future-dated transactions are not allowed to prevent data entry errors.",
              date, fileContext.lineNumber(), fileContext.fileName()),
          BudgetAnalyzerError.TRANSACTION_DATE_TOO_FAR_IN_FUTURE.name());
    }
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
    } catch (DateTimeParseException d) {
      try {
        return parseWithSimplifiedFormat(dateFormat, rawDate);
      } catch (Exception e) {
        throw new BusinessException(
            String.format(
                "Invalid date value '%s' at line %d in file '%s'",
                rawDate, fileContext.lineNumber(), fileContext.fileName()),
            BudgetAnalyzerError.CSV_PARSING_ERROR.name());
      }
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

  /** Helper class to track filename and line number during import for error messages. */
  private record CsvFileContext(String fileName, String format, CsvRow csvRow) {
    int lineNumber() {
      return csvRow.lineNumber();
    }

    Map<String, String> rowMap() {
      return csvRow.values();
    }
  }
}
