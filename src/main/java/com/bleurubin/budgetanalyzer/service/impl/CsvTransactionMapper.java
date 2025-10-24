package com.bleurubin.budgetanalyzer.service.impl;

import com.bleurubin.budgetanalyzer.config.CsvConfig;
import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.domain.TransactionType;
import com.bleurubin.budgetanalyzer.util.JsonUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.TemporalAccessor;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CsvTransactionMapper {

  private static final Map<String, TransactionType> transactionTypeMap = new HashMap<>();

  static {
    add(TransactionType.CREDIT, "credit", "deposit");
    add(TransactionType.DEBIT, "debit", "withdrawal");
  }

  private final Logger log = LoggerFactory.getLogger(this.getClass());
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

  private static void add(TransactionType type, String... aliases) {
    for (String alias : aliases) {
      transactionTypeMap.put(alias.toLowerCase(Locale.ROOT), type);
    }
  }

  public Transaction map(String format, String accountId, Map<String, String> csvRow) {
    var csvConfig = csvConfigMap.get(format);
    log.debug("Processing row: {}", JsonUtils.toJson(csvRow));

    if (csvConfig == null) {
      throw new IllegalArgumentException("No csvConfig found for bank " + format);
    }

    var rv = new Transaction();
    rv.setAccountId(accountId);
    rv.setBankName(csvConfig.bankName());
    // if we need to support multiple currencies for a given bank we can pass
    // it through with accountId. just use the default for now
    rv.setCurrencyIsoCode(csvConfig.defaultCurrencyIsoCode());

    var typeHeader = csvConfig.typeHeader();
    var amountHeader = csvConfig.debitHeader();
    /*
     * Some csv files show a single amount column and a type column to indicate
     * DEBIT or CREDIT,
     * others have separate columns for each and the type is implicit.
     */
    if (typeHeader != null) {
      var rawType = csvRow.get(typeHeader);
      if (rawType == null) {
        // FIXME- create a parsing exception
        throw new IllegalArgumentException(
            "No value found for column " + typeHeader + " in file for format: " + format);
      }

      var type = parseTransactionType(rawType);
      rv.setType(type);

      if (type == TransactionType.CREDIT) {
        amountHeader = csvConfig.creditHeader();
      }
    } else {
      // handle debit/credit as separate columns
      var type = TransactionType.DEBIT;
      var rawAmount = csvRow.get(amountHeader);

      if (rawAmount == null || rawAmount.isBlank()) {
        type = TransactionType.CREDIT;
        amountHeader = csvConfig.creditHeader();
      }

      rv.setType(type);
    }

    var amountSanitized = sanitizeNumberField(csvRow.get(amountHeader));
    var realAmount = new BigDecimal(amountSanitized);
    rv.setAmount(realAmount);

    var dateHeader = csvConfig.dateHeader();
    var rawDate = csvRow.get(dateHeader);
    if (rawDate == null) {
      // FIXME- create a parsing exception
      throw new IllegalArgumentException(
          "No value found for column " + dateHeader + " in file for format: " + format);
    }

    var date = parseDate(rawDate, csvConfig.dateFormat());
    rv.setDate(date);

    var descriptionHeader = csvConfig.descriptionHeader();
    rv.setDescription(csvRow.get(descriptionHeader));

    return rv;
  }

  /*
   * It wouldn't be practical to put this higher in the call chain
   * for user input validation since these are multiple rows in a file.
   */
  private TransactionType parseTransactionType(String val) {
    Objects.requireNonNull(val, "Type must not be null");

    var rv = transactionTypeMap.get(val.trim().toLowerCase(Locale.ROOT));
    if (rv == null) {
      throw new IllegalArgumentException("Unknown transaction type: " + val);
    }

    return rv;
  }

  /*
   * Find a cached formatter for the given dateFormat.  If it fails, try stripping
   * the time fields from the rawDate input.  If that succeeds, lazily cache the
   * date only pattern
   */
  private LocalDate parseDate(String rawDate, String dateFormat) {
    Objects.requireNonNull(rawDate, "rawDate must not be null");
    Objects.requireNonNull(dateFormat, "dateFormat must not be null");

    var formatter = dateFormatterMap.get(dateFormat);
    TemporalAccessor accessor = null;

    try {
      accessor = formatter.parse(rawDate);
    } catch (DateTimeParseException e) {
      var simpleFormatter = getSimpleFormatter(dateFormat);
      accessor = simpleFormatter.parse(rawDate);
    }

    return LocalDate.from(accessor);
  }

  /*
   * Strip the time fields from the date format and try again.  Some banks
   * send both a LocalDate format and a LocalDateTime format depending on
   * the transaction.
   */
  private DateTimeFormatter getSimpleFormatter(String dateFormat) {
    var simplifiedPattern =
        dateFormat
            .replaceAll("\\s*HH(:mm(:ss)?)?", "") // remove HH:mm or HH:mm:ss
            .trim();

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

  private String sanitizeNumberField(String val) {
    return val.replaceAll("[$,]", "");
  }
}
