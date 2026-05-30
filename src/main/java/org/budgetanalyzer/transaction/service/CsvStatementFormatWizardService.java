package org.budgetanalyzer.transaction.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.budgetanalyzer.core.csv.CsvData;
import org.budgetanalyzer.core.csv.CsvParser;
import org.budgetanalyzer.service.api.FieldError;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.StatementFormatScope;
import org.budgetanalyzer.transaction.service.dto.CsvColumnParserConfig;
import org.budgetanalyzer.transaction.service.dto.CsvWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.CsvWizardAnalysisResult;
import org.budgetanalyzer.transaction.service.dto.CsvWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.CsvWizardMappingPreviewCommand;
import org.budgetanalyzer.transaction.service.dto.CsvWizardPreviewResult;
import org.budgetanalyzer.transaction.service.dto.CsvWizardSaveCommand;
import org.budgetanalyzer.transaction.service.dto.CsvWizardValidationWarning;
import org.budgetanalyzer.transaction.service.dto.StatementFormatCommand;
import org.budgetanalyzer.transaction.service.extractor.ConfigurableCsvStatementExtractor;

/** Service for stateless CSV statement format wizard analysis, preview, and save. */
@Service
public class CsvStatementFormatWizardService {

  private static final int SAMPLE_ROW_LIMIT = 10;
  private static final int MIN_VALID_ROW_COUNT = 1;
  private static final List<String> SUPPORTED_DATE_FORMATS =
      List.of(
          "MM/dd/uu",
          "M/d/uu",
          "MM/dd/uuuu",
          "M/d/uuuu",
          "dd/MM/uu",
          "d/M/uu",
          "dd/MM/uuuu",
          "d/M/uuuu",
          "uuuu-MM-dd",
          "d MMM uuuu",
          "dd MMM uuuu",
          "d MMM uuuu HH:mm",
          "dd MMM uuuu HH:mm");

  private final CsvParser csvParser;
  private final StatementFormatService statementFormatService;

  /**
   * Constructs a CSV statement format wizard service.
   *
   * @param csvParser CSV parser for sample uploads
   * @param statementFormatService statement format persistence service
   */
  public CsvStatementFormatWizardService(
      CsvParser csvParser, StatementFormatService statementFormatService) {
    this.csvParser = csvParser;
    this.statementFormatService = statementFormatService;
  }

  /**
   * Analyzes a sample CSV and infers likely transaction column mappings.
   *
   * @param fileContent uploaded CSV bytes
   * @param filename uploaded filename
   * @return headers, sample rows, inferred mapping, confidence, and warnings
   */
  public CsvWizardAnalysisResult analyze(byte[] fileContent, String filename) {
    var csvData = parseCsv(fileContent, filename, "csv-wizard-analysis");
    var headers = csvData.headers();
    var warnings = new ArrayList<CsvWizardValidationWarning>();
    if (headers.isEmpty()) {
      warnings.add(new CsvWizardValidationWarning("file", "CSV file has no header row."));
    }
    if (csvData.rows().isEmpty()) {
      warnings.add(new CsvWizardValidationWarning("file", "CSV file has no data rows."));
    }

    var columnConfidences = new HashMap<String, Double>();
    var dateColumn = bestHeader(headers, HeaderKind.DATE, columnConfidences, "dateColumn");
    var descriptionColumn =
        bestHeader(headers, HeaderKind.DESCRIPTION, columnConfidences, "descriptionColumn");
    var amountColumn = bestHeader(headers, HeaderKind.AMOUNT, columnConfidences, "amountColumn");
    var debitColumn = bestHeader(headers, HeaderKind.DEBIT, columnConfidences, "debitColumn");
    var creditColumn = bestHeader(headers, HeaderKind.CREDIT, columnConfidences, "creditColumn");
    var typeColumn = bestHeader(headers, HeaderKind.TYPE, columnConfidences, "typeColumn");
    var categoryColumn =
        bestHeader(headers, HeaderKind.CATEGORY, columnConfidences, "categoryColumn");
    var amountMode = inferAmountMode(amountColumn, debitColumn, creditColumn, typeColumn, warnings);
    var dateFormat = inferDateFormat(dateColumn, csvData, warnings);

    addMissingInferenceWarnings(dateColumn, descriptionColumn, amountMode, warnings);
    var mapping =
        new CsvWizardColumnMapping(
            dateColumn,
            dateFormat,
            descriptionColumn,
            amountMode,
            amountColumn,
            debitColumn,
            creditColumn,
            typeColumn,
            categoryColumn);

    return new CsvWizardAnalysisResult(
        headers,
        sampleRows(csvData),
        mapping,
        calculateConfidence(columnConfidences, amountMode),
        Map.copyOf(columnConfidences),
        List.copyOf(warnings));
  }

  /**
   * Parses read-only wizard preview rows with an in-memory CSV mapping.
   *
   * @param fileContent uploaded CSV bytes
   * @param filename uploaded filename
   * @param command preview command
   * @return parsed preview rows
   */
  public CsvWizardPreviewResult preview(
      byte[] fileContent, String filename, CsvWizardMappingPreviewCommand command) {
    var csvData = parseCsv(fileContent, filename, "csv-wizard-preview");
    validateMapping(
        command.bankName(), command.defaultCurrencyIsoCode(), command.mapping(), csvData);
    var transactions =
        parsePreviewRows(
            fileContent,
            command.bankName(),
            command.defaultCurrencyIsoCode(),
            command.accountId(),
            command.mapping());
    return new CsvWizardPreviewResult(transactions, List.of());
  }

  /**
   * Saves a user-scoped CSV statement format after validating its confirmed mapping.
   *
   * @param fileContent uploaded CSV bytes
   * @param filename uploaded filename
   * @param command save command
   * @param userId current user ID
   * @param canWriteAny whether the caller can create system formats
   * @return saved statement format
   */
  @Transactional
  public StatementFormat save(
      byte[] fileContent,
      String filename,
      CsvWizardSaveCommand command,
      String userId,
      boolean canWriteAny) {
    var csvData = parseCsv(fileContent, filename, "csv-wizard-save");
    validateMapping(
        command.bankName(), command.defaultCurrencyIsoCode(), command.mapping(), csvData);
    parsePreviewRows(
        fileContent, command.bankName(), command.defaultCurrencyIsoCode(), null, command.mapping());

    var statementFormatCommand =
        new StatementFormatCommand(
            command.displayName(),
            FormatType.CSV,
            command.bankName(),
            command.defaultCurrencyIsoCode(),
            StatementFormatScope.USER,
            command.mapping().dateColumn(),
            command.mapping().dateFormat(),
            command.mapping().descriptionColumn(),
            creditHeader(command.mapping()),
            debitHeader(command.mapping()),
            typeHeader(command.mapping()),
            command.mapping().categoryColumn());
    return statementFormatService.createFormat(statementFormatCommand, userId, canWriteAny);
  }

  private CsvData parseCsv(byte[] fileContent, String filename, String format) {
    try {
      return csvParser.parseCsvInputStream(new ByteArrayInputStream(fileContent), filename, format);
    } catch (IOException ioException) {
      throw new BusinessException(
          "Failed to parse CSV sample: " + ioException.getMessage(),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name(),
          ioException);
    }
  }

  private List<Map<String, String>> sampleRows(CsvData csvData) {
    return csvData.rows().stream()
        .limit(SAMPLE_ROW_LIMIT)
        .map(csvRow -> Map.copyOf(csvRow.values()))
        .toList();
  }

  private String bestHeader(
      List<String> headers,
      HeaderKind headerKind,
      Map<String, Double> columnConfidences,
      String confidenceKey) {
    var bestHeader = (String) null;
    var bestScore = 0.0;
    for (var header : headers) {
      var score = scoreHeader(header, headerKind);
      if (score > bestScore) {
        bestScore = score;
        bestHeader = header;
      }
    }
    columnConfidences.put(confidenceKey, bestScore);
    return bestScore >= 0.55 ? bestHeader : null;
  }

  private double scoreHeader(String header, HeaderKind headerKind) {
    var normalizedHeader = normalize(header);
    return switch (headerKind) {
      case DATE -> scoreAny(normalizedHeader, "date", "transactiondate", "postingdate", "posted");
      case DESCRIPTION ->
          scoreAny(
              normalizedHeader,
              "description",
              "memo",
              "particulars",
              "narrative",
              "payee",
              "merchant",
              "details");
      case AMOUNT -> scoreAny(normalizedHeader, "amount", "transactionamount", "value");
      case DEBIT -> scoreAny(normalizedHeader, "debit", "withdrawal", "withdraw", "outflow");
      case CREDIT -> scoreAny(normalizedHeader, "credit", "deposit", "payment", "inflow");
      case TYPE -> scoreAny(normalizedHeader, "type", "transactiontype", "creditdebit", "drcr");
      case CATEGORY -> scoreAny(normalizedHeader, "category", "classification");
    };
  }

  private double scoreAny(String normalizedHeader, String... candidates) {
    for (var candidate : candidates) {
      if (normalizedHeader.equals(candidate)) {
        return 0.95;
      }
    }
    for (var candidate : candidates) {
      if (normalizedHeader.contains(candidate)) {
        return 0.75;
      }
    }
    return 0.0;
  }

  private String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  private CsvWizardAmountMode inferAmountMode(
      String amountColumn,
      String debitColumn,
      String creditColumn,
      String typeColumn,
      List<CsvWizardValidationWarning> warnings) {
    if (debitColumn != null && creditColumn != null) {
      return CsvWizardAmountMode.DEBIT_CREDIT_COLUMNS;
    }
    if (amountColumn != null) {
      if (typeColumn == null) {
        warnings.add(
            new CsvWizardValidationWarning(
                "typeColumn", "A single amount column also needs a credit/debit type column."));
      }
      return CsvWizardAmountMode.SINGLE_AMOUNT_WITH_TYPE;
    }
    warnings.add(new CsvWizardValidationWarning("amountColumn", "No amount column was inferred."));
    return null;
  }

  private String inferDateFormat(
      String dateColumn, CsvData csvData, List<CsvWizardValidationWarning> warnings) {
    if (dateColumn == null) {
      return null;
    }
    var dateValues =
        csvData.rows().stream()
            .map(csvRow -> csvRow.values().get(dateColumn))
            .filter(value -> value != null && !value.isBlank())
            .limit(SAMPLE_ROW_LIMIT)
            .toList();
    for (var supportedDateFormat : SUPPORTED_DATE_FORMATS) {
      if (dateValues.stream().allMatch(value -> canParseDate(value, supportedDateFormat))) {
        return supportedDateFormat;
      }
    }
    warnings.add(new CsvWizardValidationWarning("dateFormat", "No supported date format matched."));
    return SUPPORTED_DATE_FORMATS.getFirst();
  }

  private boolean canParseDate(String value, String dateFormat) {
    try {
      LocalDate.from(buildDateFormatter(dateFormat).parse(value));
      return true;
    } catch (DateTimeParseException dateTimeParseException) {
      return canParseDateWithSimplifiedFormat(value, dateFormat);
    }
  }

  private boolean canParseDateWithSimplifiedFormat(String value, String dateFormat) {
    var simplifiedPattern = dateFormat.replaceAll("\\s*HH(:mm(:ss)?)?", "").trim();
    if (simplifiedPattern.equals(dateFormat)) {
      return false;
    }
    try {
      LocalDate.from(buildDateFormatter(simplifiedPattern).parse(value));
      return true;
    } catch (DateTimeParseException dateTimeParseException) {
      return false;
    }
  }

  private DateTimeFormatter buildDateFormatter(String dateFormat) {
    return DateTimeFormatter.ofPattern(dateFormat, Locale.ROOT)
        .withResolverStyle(ResolverStyle.SMART);
  }

  private void addMissingInferenceWarnings(
      String dateColumn,
      String descriptionColumn,
      CsvWizardAmountMode amountMode,
      List<CsvWizardValidationWarning> warnings) {
    if (dateColumn == null) {
      warnings.add(new CsvWizardValidationWarning("dateColumn", "No date column was inferred."));
    }
    if (descriptionColumn == null) {
      warnings.add(
          new CsvWizardValidationWarning(
              "descriptionColumn", "No description column was inferred."));
    }
    if (amountMode == null) {
      warnings.add(new CsvWizardValidationWarning("amountMode", "No amount mode was inferred."));
    }
  }

  private double calculateConfidence(
      Map<String, Double> columnConfidences, CsvWizardAmountMode amountMode) {
    var keys = new ArrayList<>(List.of("dateColumn", "descriptionColumn"));
    if (amountMode == CsvWizardAmountMode.SINGLE_AMOUNT_WITH_TYPE) {
      keys.add("amountColumn");
      keys.add("typeColumn");
    } else if (amountMode == CsvWizardAmountMode.DEBIT_CREDIT_COLUMNS) {
      keys.add("debitColumn");
      keys.add("creditColumn");
    }
    return keys.stream()
        .map(columnConfidences::get)
        .filter(Objects::nonNull)
        .mapToDouble(Double::doubleValue)
        .average()
        .orElse(0.0);
  }

  private void validateMapping(
      String bankName,
      String defaultCurrencyIsoCode,
      CsvWizardColumnMapping mapping,
      CsvData csvData) {
    var fieldErrors = new ArrayList<FieldError>();
    validateBankAndCurrency(bankName, defaultCurrencyIsoCode, fieldErrors);
    if (mapping == null) {
      fieldErrors.add(FieldError.of("mapping", "CSV column mapping is required.", null));
      throwValidationException(fieldErrors);
    }

    validateColumn("mapping.dateColumn", mapping.dateColumn(), csvData.headers(), fieldErrors);
    validateColumn(
        "mapping.descriptionColumn", mapping.descriptionColumn(), csvData.headers(), fieldErrors);
    validateDateFormat(mapping.dateFormat(), fieldErrors);
    validateAmountMapping(mapping, csvData.headers(), fieldErrors);
    validateOptionalColumn(
        "mapping.categoryColumn", mapping.categoryColumn(), csvData.headers(), fieldErrors);
    if (csvData.rows().isEmpty()) {
      fieldErrors.add(
          FieldError.of("file", "CSV sample must contain at least one data row.", null));
    }

    if (!fieldErrors.isEmpty()) {
      throwValidationException(fieldErrors);
    }
  }

  private void validateBankAndCurrency(
      String bankName, String defaultCurrencyIsoCode, List<FieldError> fieldErrors) {
    if (bankName == null || bankName.isBlank()) {
      fieldErrors.add(FieldError.of("bankName", "Bank name is required.", bankName));
    }
    if (defaultCurrencyIsoCode == null || defaultCurrencyIsoCode.isBlank()) {
      fieldErrors.add(
          FieldError.of(
              "defaultCurrencyIsoCode",
              "Default currency ISO code is required.",
              defaultCurrencyIsoCode));
      return;
    }
    try {
      Currency.getInstance(defaultCurrencyIsoCode.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException illegalArgumentException) {
      fieldErrors.add(
          FieldError.of(
              "defaultCurrencyIsoCode",
              "Default currency ISO code must be a valid ISO 4217 code.",
              defaultCurrencyIsoCode));
    }
  }

  private void validateColumn(
      String field, String column, List<String> headers, List<FieldError> fieldErrors) {
    if (column == null || column.isBlank()) {
      fieldErrors.add(FieldError.of(field, "Column is required.", column));
      return;
    }
    if (!headers.contains(column)) {
      fieldErrors.add(FieldError.of(field, "Column does not exist in the CSV header row.", column));
    }
  }

  private void validateOptionalColumn(
      String field, String column, List<String> headers, List<FieldError> fieldErrors) {
    if (column == null || column.isBlank()) {
      return;
    }
    if (!headers.contains(column)) {
      fieldErrors.add(FieldError.of(field, "Column does not exist in the CSV header row.", column));
    }
  }

  private void validateDateFormat(String dateFormat, List<FieldError> fieldErrors) {
    if (dateFormat == null || dateFormat.isBlank()) {
      fieldErrors.add(FieldError.of("mapping.dateFormat", "Date format is required.", dateFormat));
      return;
    }
    if (!SUPPORTED_DATE_FORMATS.contains(dateFormat)) {
      fieldErrors.add(
          FieldError.of(
              "mapping.dateFormat", "Date format is not supported by the CSV wizard.", dateFormat));
      return;
    }
    try {
      buildDateFormatter(dateFormat);
    } catch (IllegalArgumentException illegalArgumentException) {
      fieldErrors.add(
          FieldError.of("mapping.dateFormat", "Date format pattern is invalid.", dateFormat));
    }
  }

  private void validateAmountMapping(
      CsvWizardColumnMapping mapping, List<String> headers, List<FieldError> fieldErrors) {
    if (mapping.amountMode() == null) {
      fieldErrors.add(FieldError.of("mapping.amountMode", "Amount mode is required.", null));
      return;
    }
    if (mapping.amountMode() == CsvWizardAmountMode.SINGLE_AMOUNT_WITH_TYPE) {
      validateColumn("mapping.amountColumn", mapping.amountColumn(), headers, fieldErrors);
      validateColumn("mapping.typeColumn", mapping.typeColumn(), headers, fieldErrors);
      return;
    }
    validateColumn("mapping.debitColumn", mapping.debitColumn(), headers, fieldErrors);
    validateColumn("mapping.creditColumn", mapping.creditColumn(), headers, fieldErrors);
  }

  private List<org.budgetanalyzer.transaction.service.dto.PreviewTransaction> parsePreviewRows(
      byte[] fileContent,
      String bankName,
      String defaultCurrencyIsoCode,
      String accountId,
      CsvWizardColumnMapping mapping) {
    var statementFormat =
        StatementFormat.createCsvFormat(
            "CSV Wizard Preview", bankName, defaultCurrencyIsoCode.toUpperCase(Locale.ROOT), null);
    var parserRevision = ParserRevision.createCsvColumnConfig(statementFormat, 1, "{}");
    var csvColumnParserConfig =
        new CsvColumnParserConfig(
            mapping.dateColumn(),
            mapping.dateFormat(),
            mapping.descriptionColumn(),
            creditHeader(mapping),
            debitHeader(mapping),
            typeHeader(mapping),
            mapping.categoryColumn());
    var extractor =
        new ConfigurableCsvStatementExtractor(
            statementFormat, parserRevision, csvColumnParserConfig, csvParser);
    try {
      var transactions = extractor.extract(fileContent, accountId);
      if (transactions.size() < MIN_VALID_ROW_COUNT) {
        throw new BusinessException(
            "CSV wizard mapping did not parse enough valid transaction rows.",
            BudgetAnalyzerError.CSV_WIZARD_VALIDATION_FAILED.name(),
            List.of(
                FieldError.of(
                    "mapping", "Mapping must parse at least one valid transaction row.", null)));
      }
      return transactions;
    } catch (BusinessException businessException) {
      if (businessException.hasFieldErrors()) {
        throw businessException;
      }
      throw new BusinessException(
          "CSV wizard mapping validation failed.",
          BudgetAnalyzerError.CSV_WIZARD_VALIDATION_FAILED.name(),
          List.of(
              FieldError.of(
                  resolveParserErrorField(businessException),
                  businessException.getMessage(),
                  null)));
    }
  }

  private String resolveParserErrorField(BusinessException businessException) {
    var message = businessException.getMessage().toLowerCase(Locale.ROOT);
    if (message.contains("date")) {
      return "mapping.dateColumn";
    }
    if (message.contains("amount")) {
      return "mapping.amountColumn";
    }
    if (message.contains("type")) {
      return "mapping.typeColumn";
    }
    return "mapping";
  }

  private String creditHeader(CsvWizardColumnMapping mapping) {
    if (mapping.amountMode() == CsvWizardAmountMode.SINGLE_AMOUNT_WITH_TYPE) {
      return mapping.amountColumn();
    }
    return mapping.creditColumn();
  }

  private String debitHeader(CsvWizardColumnMapping mapping) {
    if (mapping.amountMode() == CsvWizardAmountMode.SINGLE_AMOUNT_WITH_TYPE) {
      return mapping.amountColumn();
    }
    return mapping.debitColumn();
  }

  private String typeHeader(CsvWizardColumnMapping mapping) {
    if (mapping.amountMode() == CsvWizardAmountMode.SINGLE_AMOUNT_WITH_TYPE) {
      return mapping.typeColumn();
    }
    return null;
  }

  private void throwValidationException(List<FieldError> fieldErrors) {
    throw new BusinessException(
        "CSV wizard mapping validation failed.",
        BudgetAnalyzerError.CSV_WIZARD_VALIDATION_FAILED.name(),
        fieldErrors);
  }

  private enum HeaderKind {
    DATE,
    DESCRIPTION,
    AMOUNT,
    DEBIT,
    CREDIT,
    TYPE,
    CATEGORY
  }
}
