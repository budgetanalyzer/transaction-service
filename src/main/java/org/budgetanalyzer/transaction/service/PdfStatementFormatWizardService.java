package org.budgetanalyzer.transaction.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.service.api.FieldError;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableFileType;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableParserConfig;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableYearSource;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAnalysisResult;
import org.budgetanalyzer.transaction.service.dto.PdfWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.PdfWizardMappingPreviewCommand;
import org.budgetanalyzer.transaction.service.dto.PdfWizardPreviewResult;
import org.budgetanalyzer.transaction.service.dto.PdfWizardSaveCommand;
import org.budgetanalyzer.transaction.service.dto.PdfWizardTableCandidate;
import org.budgetanalyzer.transaction.service.extractor.ConfigurablePdfTextTableStatementExtractor;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextExtractionService;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextTableCandidate;

/** Service for stateless text-PDF statement format wizard analysis. */
@Service
public class PdfStatementFormatWizardService {

  private static final int MIN_TRANSACTION_ROWS = 1;
  private static final double MIN_CONFIDENT_CANDIDATE_SCORE = 0.55;
  private static final Pattern YEARLESS_NUMERIC_DATE_PATTERN =
      Pattern.compile("\\b\\d{1,2}/\\d{1,2}\\b");
  private static final Pattern NUMERIC_DATE_PATTERN =
      Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b");
  private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b");
  private static final Pattern MONTH_DATE_PATTERN =
      Pattern.compile(
          "\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\s+"
              + "\\d{1,2}(?:,?\\s+\\d{4})?\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern AMOUNT_PATTERN =
      Pattern.compile("^\\(?-?\\$?\\s*\\d+(?:\\.\\d{2})?\\)?$");

  private final PdfTextExtractionService pdfTextExtractionService;
  private final StatementFormatRepository statementFormatRepository;
  private final ParserRevisionRepository parserRevisionRepository;
  private final ObjectMapper objectMapper;
  private final PdfTextTableParserConfigValidator pdfTextTableParserConfigValidator =
      new PdfTextTableParserConfigValidator();

  /**
   * Constructs a PDF statement format wizard service.
   *
   * @param pdfTextExtractionService text-PDF extraction service
   * @param statementFormatRepository repository for statement format persistence
   * @param parserRevisionRepository repository for parser revision persistence
   * @param objectMapper JSON mapper for parser configuration
   */
  public PdfStatementFormatWizardService(
      PdfTextExtractionService pdfTextExtractionService,
      StatementFormatRepository statementFormatRepository,
      ParserRevisionRepository parserRevisionRepository,
      ObjectMapper objectMapper) {
    this.pdfTextExtractionService = pdfTextExtractionService;
    this.statementFormatRepository = statementFormatRepository;
    this.parserRevisionRepository = parserRevisionRepository;
    this.objectMapper = objectMapper;
  }

  /**
   * Analyzes a sample PDF and returns ranked transaction-table candidates.
   *
   * @param fileContent uploaded PDF bytes
   * @param filename uploaded filename
   * @return ranked candidates, confidence, and unsupported-file reasons
   */
  public PdfWizardAnalysisResult analyze(byte[] fileContent, String filename) {
    try {
      var pdfTextDocument = pdfTextExtractionService.extract(fileContent, filename);
      var candidates =
          pdfTextDocument.tableCandidates().stream()
              .map(this::scoreCandidate)
              .sorted(Comparator.comparing(PdfWizardTableCandidate::confidence).reversed())
              .toList();
      var rejectionReasons = topLevelRejectionReasons(candidates);
      var confidence = candidates.isEmpty() ? 0.0 : candidates.getFirst().confidence();
      return new PdfWizardAnalysisResult(candidates, confidence, rejectionReasons);
    } catch (BusinessException businessException) {
      if (BudgetAnalyzerError.PDF_PARSING_ERROR.name().equals(businessException.getCode())) {
        return new PdfWizardAnalysisResult(List.of(), 0.0, List.of(businessException.getMessage()));
      }
      throw businessException;
    }
  }

  /**
   * Parses read-only wizard preview rows with an in-memory PDF text-table mapping.
   *
   * @param fileContent uploaded PDF bytes
   * @param filename uploaded filename
   * @param command preview command
   * @return parsed preview rows and diagnostics
   */
  public PdfWizardPreviewResult preview(
      byte[] fileContent, String filename, PdfWizardMappingPreviewCommand command) {
    validateBankAndCurrency(command.bankName(), command.defaultCurrencyIsoCode());
    var pdfTextTableParserConfig =
        buildParserConfig(
            command.headerMustContain(),
            command.minimumRows(),
            command.yearSource(),
            command.mapping());
    var fieldErrors = pdfTextTableParserConfigValidator.validate(pdfTextTableParserConfig);
    if (!fieldErrors.isEmpty()) {
      throw new BusinessException(
          "PDF wizard mapping validation failed.",
          BudgetAnalyzerError.PDF_WIZARD_VALIDATION_FAILED.name(),
          fieldErrors);
    }
    var transactions =
        parsePreviewRows(
            fileContent,
            filename,
            command.bankName(),
            command.defaultCurrencyIsoCode(),
            command.accountId(),
            pdfTextTableParserConfig);
    return new PdfWizardPreviewResult(
        transactions,
        List.of(
            "Matched a text-PDF table using "
                + pdfTextTableParserConfig.headerMustContain().size()
                + " configured header token(s)."));
  }

  /**
   * Saves a user-scoped PDF statement format after validating its confirmed mapping.
   *
   * @param fileContent uploaded PDF bytes
   * @param filename uploaded filename
   * @param command save command
   * @param userId current user ID
   * @return saved statement format
   */
  @Transactional
  public StatementFormat save(
      byte[] fileContent, String filename, PdfWizardSaveCommand command, String userId) {
    validateDisplayName(command.displayName());
    validateBankAndCurrency(command.bankName(), command.defaultCurrencyIsoCode());
    var pdfTextTableParserConfig =
        buildParserConfig(
            command.headerMustContain(),
            command.minimumRows(),
            command.yearSource(),
            command.mapping());
    var fieldErrors = pdfTextTableParserConfigValidator.validate(pdfTextTableParserConfig);
    if (!fieldErrors.isEmpty()) {
      throw new BusinessException(
          "PDF wizard mapping validation failed.",
          BudgetAnalyzerError.PDF_WIZARD_VALIDATION_FAILED.name(),
          fieldErrors);
    }

    parsePreviewRows(
        fileContent,
        filename,
        command.bankName(),
        command.defaultCurrencyIsoCode(),
        null,
        pdfTextTableParserConfig);

    var statementFormat =
        StatementFormat.createUserPdfFormat(
            command.displayName(),
            command.bankName(),
            command.defaultCurrencyIsoCode().toUpperCase(Locale.ROOT),
            userId);
    var savedStatementFormat = statementFormatRepository.save(statementFormat);
    var parserRevision =
        ParserRevision.createPdfTextTableConfig(
            savedStatementFormat, 1, serializeParserConfig(pdfTextTableParserConfig));
    parserRevisionRepository.save(parserRevision);
    return savedStatementFormat;
  }

  private PdfTextTableParserConfig buildParserConfig(
      List<String> headerMustContain,
      Integer minimumRows,
      PdfTextTableYearSource yearSource,
      PdfWizardColumnMapping mapping) {
    if (mapping == null) {
      throw new BusinessException(
          "PDF wizard mapping validation failed.",
          BudgetAnalyzerError.PDF_WIZARD_VALIDATION_FAILED.name(),
          List.of(FieldError.of("mapping", "PDF column mapping is required.", null)));
    }
    var configuredHeaderMustContain = configuredHeaderTokens(headerMustContain, mapping);
    return new PdfTextTableParserConfig(
        PdfTextTableFileType.TEXT_PDF,
        configuredHeaderMustContain,
        minimumRows == null ? MIN_TRANSACTION_ROWS : minimumRows,
        mapping.dateHeader(),
        mapping.dateFormat(),
        mapping.descriptionHeader(),
        mapping.amountMode() == PdfWizardAmountMode.SIGNED_AMOUNT ? mapping.amountHeader() : null,
        mapping.amountMode() == PdfWizardAmountMode.DEBIT_CREDIT_COLUMNS
            ? mapping.debitHeader()
            : null,
        mapping.amountMode() == PdfWizardAmountMode.DEBIT_CREDIT_COLUMNS
            ? mapping.creditHeader()
            : null,
        mapping.typeHeader(),
        mapping.negativeMeans(),
        yearSource == null ? PdfTextTableYearSource.EXPLICIT_DATE : yearSource);
  }

  private List<String> configuredHeaderTokens(
      List<String> headerMustContain, PdfWizardColumnMapping mapping) {
    if (headerMustContain != null
        && headerMustContain.stream().anyMatch(value -> value != null && !value.isBlank())) {
      return headerMustContain.stream().filter(value -> value != null && !value.isBlank()).toList();
    }
    var headers = new ArrayList<String>();
    addHeader(headers, mapping.dateHeader());
    addHeader(headers, mapping.descriptionHeader());
    if (mapping.amountMode() == PdfWizardAmountMode.SIGNED_AMOUNT) {
      addHeader(headers, mapping.amountHeader());
    } else {
      addHeader(headers, mapping.debitHeader());
      addHeader(headers, mapping.creditHeader());
    }
    addHeader(headers, mapping.typeHeader());
    return List.copyOf(headers);
  }

  private void addHeader(List<String> headers, String header) {
    if (header != null && !header.isBlank()) {
      headers.add(header);
    }
  }

  private void validateBankAndCurrency(String bankName, String defaultCurrencyIsoCode) {
    var fieldErrors = new ArrayList<FieldError>();
    if (bankName == null || bankName.isBlank()) {
      fieldErrors.add(FieldError.of("bankName", "Bank name is required.", bankName));
    }
    if (defaultCurrencyIsoCode == null || defaultCurrencyIsoCode.isBlank()) {
      fieldErrors.add(
          FieldError.of(
              "defaultCurrencyIsoCode",
              "Default currency ISO code is required.",
              defaultCurrencyIsoCode));
    } else {
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
    if (!fieldErrors.isEmpty()) {
      throw new BusinessException(
          "PDF wizard mapping validation failed.",
          BudgetAnalyzerError.PDF_WIZARD_VALIDATION_FAILED.name(),
          fieldErrors);
    }
  }

  private void validateDisplayName(String displayName) {
    if (displayName == null || displayName.isBlank()) {
      throw new BusinessException(
          "PDF wizard mapping validation failed.",
          BudgetAnalyzerError.PDF_WIZARD_VALIDATION_FAILED.name(),
          List.of(FieldError.of("displayName", "Display name is required.", displayName)));
    }
  }

  private List<org.budgetanalyzer.transaction.service.dto.PreviewTransaction> parsePreviewRows(
      byte[] fileContent,
      String filename,
      String bankName,
      String defaultCurrencyIsoCode,
      String accountId,
      PdfTextTableParserConfig pdfTextTableParserConfig) {
    var statementFormat =
        StatementFormat.createUserPdfFormat(
            "PDF Wizard Preview", bankName, defaultCurrencyIsoCode.toUpperCase(Locale.ROOT), null);
    var parserRevision = ParserRevision.createPdfTextTableConfig(statementFormat, 1, "{}");
    var statementExtractor =
        new ConfigurablePdfTextTableStatementExtractor(
            statementFormat, parserRevision, pdfTextTableParserConfig, pdfTextExtractionService);
    try {
      return statementExtractor.extract(fileContent, filename, accountId);
    } catch (BusinessException businessException) {
      if (businessException.hasFieldErrors()) {
        throw businessException;
      }
      throw new BusinessException(
          "PDF wizard mapping validation failed.",
          BudgetAnalyzerError.PDF_WIZARD_VALIDATION_FAILED.name(),
          List.of(
              FieldError.of(
                  resolveParserErrorField(businessException),
                  businessException.getMessage(),
                  null)));
    }
  }

  private String serializeParserConfig(PdfTextTableParserConfig pdfTextTableParserConfig) {
    try {
      return objectMapper.writeValueAsString(pdfTextTableParserConfig);
    } catch (JsonProcessingException jsonProcessingException) {
      throw new BusinessException(
          "Failed to serialize PDF parser configuration.",
          BudgetAnalyzerError.PDF_WIZARD_VALIDATION_FAILED.name(),
          jsonProcessingException);
    }
  }

  private String resolveParserErrorField(BusinessException businessException) {
    var message = businessException.getMessage().toLowerCase(Locale.ROOT);
    if (message.contains("date") || message.contains("year")) {
      return "mapping.dateHeader";
    }
    if (message.contains("amount") || message.contains("debit") || message.contains("credit")) {
      return "mapping.amountHeader";
    }
    if (message.contains("type")) {
      return "mapping.typeHeader";
    }
    if (message.contains("description")) {
      return "mapping.descriptionHeader";
    }
    return "mapping";
  }

  private PdfWizardTableCandidate scoreCandidate(PdfTextTableCandidate pdfTextTableCandidate) {
    var columnConfidences = new HashMap<String, Double>();
    var dateMatch = bestColumn(pdfTextTableCandidate, HeaderKind.DATE);
    columnConfidences.put("dateHeader", dateMatch.score());
    var descriptionMatch = bestColumn(pdfTextTableCandidate, HeaderKind.DESCRIPTION);
    columnConfidences.put("descriptionHeader", descriptionMatch.score());
    var amountMatch = bestColumn(pdfTextTableCandidate, HeaderKind.AMOUNT);
    columnConfidences.put("amountHeader", amountMatch.score());
    var debitMatch = bestColumn(pdfTextTableCandidate, HeaderKind.DEBIT);
    columnConfidences.put("debitHeader", debitMatch.score());
    var creditMatch = bestColumn(pdfTextTableCandidate, HeaderKind.CREDIT);
    columnConfidences.put("creditHeader", creditMatch.score());
    var typeMatch = bestColumn(pdfTextTableCandidate, HeaderKind.TYPE);
    columnConfidences.put("typeHeader", typeMatch.score());

    var amountMode = inferAmountMode(amountMatch, debitMatch, creditMatch);
    var mapping =
        new PdfWizardColumnMapping(
            dateMatch.acceptedHeader(),
            inferDateFormat(dateMatch.sampleValues()),
            descriptionMatch.acceptedHeader(),
            amountMode,
            amountMode == PdfWizardAmountMode.SIGNED_AMOUNT ? amountMatch.acceptedHeader() : null,
            amountMode == PdfWizardAmountMode.DEBIT_CREDIT_COLUMNS
                ? debitMatch.acceptedHeader()
                : null,
            amountMode == PdfWizardAmountMode.DEBIT_CREDIT_COLUMNS
                ? creditMatch.acceptedHeader()
                : null,
            typeMatch.score() >= MIN_CONFIDENT_CANDIDATE_SCORE ? typeMatch.header() : null,
            inferNegativeMeans(amountMode, amountMatch.sampleValues(), typeMatch.acceptedHeader()));
    var rejectionReasons =
        rejectionReasons(pdfTextTableCandidate, dateMatch, descriptionMatch, amountMode, mapping);
    var confidence =
        calculateConfidence(pdfTextTableCandidate, columnConfidences, amountMode, mapping);
    return new PdfWizardTableCandidate(
        candidateId(pdfTextTableCandidate),
        pdfTextTableCandidate.pageNumber(),
        pdfTextTableCandidate.startLineNumber(),
        pdfTextTableCandidate.endLineNumber(),
        pdfTextTableCandidate.rowCount(),
        pdfTextTableCandidate.repeatedHeaderCount(),
        pdfTextTableCandidate.headerCells(),
        pdfTextTableCandidate.sampleRows(),
        mapping,
        confidence,
        Map.copyOf(columnConfidences),
        rejectionReasons);
  }

  private String candidateId(PdfTextTableCandidate pdfTextTableCandidate) {
    return "p"
        + pdfTextTableCandidate.pageNumber()
        + "-l"
        + pdfTextTableCandidate.startLineNumber()
        + "-"
        + pdfTextTableCandidate.endLineNumber();
  }

  private ColumnMatch bestColumn(
      PdfTextTableCandidate pdfTextTableCandidate, HeaderKind headerKind) {
    var headers = pdfTextTableCandidate.headerCells();
    var bestColumnIndex = -1;
    var bestScore = 0.0;
    for (var columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
      var sampleValues = sampleValues(pdfTextTableCandidate, columnIndex);
      var score = scoreColumn(headers.get(columnIndex), sampleValues, headerKind);
      if (score > bestScore) {
        bestScore = score;
        bestColumnIndex = columnIndex;
      }
    }
    if (bestColumnIndex < 0) {
      return new ColumnMatch(null, List.of(), 0.0);
    }
    return new ColumnMatch(
        headers.get(bestColumnIndex),
        sampleValues(pdfTextTableCandidate, bestColumnIndex),
        bestScore);
  }

  private double scoreColumn(String header, List<String> sampleValues, HeaderKind headerKind) {
    var headerScore = scoreHeader(header, headerKind);
    var valueScore = scoreValues(sampleValues, headerKind);
    return switch (headerKind) {
      case DATE, AMOUNT -> Math.max(headerScore, valueScore);
      case DESCRIPTION -> Math.max(headerScore, valueScore * 0.85);
      case DEBIT, CREDIT -> headerScore;
      case TYPE -> Math.max(headerScore, valueScore * 0.65);
    };
  }

  private double scoreHeader(String header, HeaderKind headerKind) {
    var normalizedHeader = normalize(header);
    return switch (headerKind) {
      case DATE -> scoreAny(normalizedHeader, "date", "transactiondate", "posted", "postingdate");
      case DESCRIPTION ->
          scoreAny(
              normalizedHeader,
              "description",
              "particulars",
              "memo",
              "details",
              "merchant",
              "payee");
      case AMOUNT -> scoreAny(normalizedHeader, "amount", "transactionamount", "value");
      case DEBIT -> scoreAny(normalizedHeader, "debit", "withdrawal", "withdraw", "outflow");
      case CREDIT -> scoreAny(normalizedHeader, "credit", "deposit", "payment", "inflow");
      case TYPE -> scoreAny(normalizedHeader, "type", "drcr", "transactiontype");
    };
  }

  private double scoreValues(List<String> sampleValues, HeaderKind headerKind) {
    var usableValues = sampleValues.stream().filter(value -> !isBlank(value)).toList();
    if (usableValues.isEmpty()) {
      return 0.0;
    }
    var matchingCount =
        usableValues.stream().filter(value -> valueMatchesHeaderKind(value, headerKind)).count();
    return (double) matchingCount / usableValues.size();
  }

  private boolean valueMatchesHeaderKind(String value, HeaderKind headerKind) {
    return switch (headerKind) {
      case DATE -> isDateLike(value);
      case AMOUNT, DEBIT, CREDIT -> isAmountLike(value) && !isDateLike(value);
      case DESCRIPTION -> !isDateLike(value) && !isAmountLike(value) && value.strip().length() >= 3;
      case TYPE -> {
        var normalizedValue = normalize(value);
        yield normalizedValue.equals("debit")
            || normalizedValue.equals("credit")
            || normalizedValue.equals("dr")
            || normalizedValue.equals("cr");
      }
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

  private List<String> sampleValues(PdfTextTableCandidate pdfTextTableCandidate, int columnIndex) {
    return pdfTextTableCandidate.sampleRows().stream()
        .map(row -> columnIndex < row.size() ? row.get(columnIndex) : null)
        .filter(Objects::nonNull)
        .toList();
  }

  private PdfWizardAmountMode inferAmountMode(
      ColumnMatch amountMatch, ColumnMatch debitMatch, ColumnMatch creditMatch) {
    var hasDebitCreditPair =
        debitMatch.score() >= MIN_CONFIDENT_CANDIDATE_SCORE
            && creditMatch.score() >= MIN_CONFIDENT_CANDIDATE_SCORE;
    if (hasDebitCreditPair) {
      return PdfWizardAmountMode.DEBIT_CREDIT_COLUMNS;
    }
    if (amountMatch.score() >= MIN_CONFIDENT_CANDIDATE_SCORE) {
      return PdfWizardAmountMode.SIGNED_AMOUNT;
    }
    return null;
  }

  private String inferDateFormat(List<String> dateValues) {
    var dateValue = dateValues.stream().filter(value -> !isBlank(value)).findFirst().orElse(null);
    if (dateValue == null) {
      return null;
    }
    var normalizedValue = dateValue.strip();
    if (ISO_DATE_PATTERN.matcher(normalizedValue).find()) {
      return "uuuu-MM-dd";
    }
    if (MONTH_DATE_PATTERN.matcher(normalizedValue).find()) {
      var hasYear = normalizedValue.matches(".*\\b\\d{4}\\b.*");
      var hasComma = normalizedValue.contains(",");
      var hasFullMonth = normalizedValue.split("\\s+")[0].length() > 3;
      if (hasFullMonth && hasComma) {
        return "MMMM d, uuuu";
      }
      if (hasFullMonth && hasYear) {
        return "MMMM d uuuu";
      }
      if (hasFullMonth) {
        return "MMMM d";
      }
      if (hasComma) {
        return "MMM d, uuuu";
      }
      return hasYear ? "MMM d uuuu" : "MMM d";
    }
    if (NUMERIC_DATE_PATTERN.matcher(normalizedValue).find()) {
      var dateParts = normalizedValue.split("/");
      if (dateParts.length >= 3) {
        return numericDateFormat(dateParts[0], dateParts[1], dateParts[2]);
      }
    }
    if (YEARLESS_NUMERIC_DATE_PATTERN.matcher(normalizedValue).find()) {
      var dateParts = normalizedValue.split("/");
      if (dateParts.length >= 2) {
        return numericDateFormat(dateParts[0], dateParts[1], null);
      }
    }
    return null;
  }

  private String numericDateFormat(String firstPart, String secondPart, String yearPart) {
    var firstMonthPattern = firstPart.length() == 1 ? "M" : "MM";
    var firstDayPattern = firstPart.length() == 1 ? "d" : "dd";
    var secondMonthPattern = secondPart.length() == 1 ? "M" : "MM";
    var secondDayPattern = secondPart.length() == 1 ? "d" : "dd";
    var yearPattern = yearPart == null ? null : yearPart.length() == 4 ? "uuuu" : "uu";
    var dayFirst = Integer.parseInt(firstPart) > 12;
    var dateFormat =
        dayFirst
            ? firstDayPattern + "/" + secondMonthPattern
            : firstMonthPattern + "/" + secondDayPattern;
    if (yearPattern == null) {
      return dateFormat;
    }
    return dateFormat + "/" + yearPattern;
  }

  private PdfTextTableNegativeMeans inferNegativeMeans(
      PdfWizardAmountMode amountMode, List<String> amountValues, String typeHeader) {
    if (amountMode != PdfWizardAmountMode.SIGNED_AMOUNT || typeHeader != null) {
      return null;
    }
    if (amountValues.stream().anyMatch(this::isNegativeAmountLike)) {
      return PdfTextTableNegativeMeans.CREDIT;
    }
    return null;
  }

  private List<String> rejectionReasons(
      PdfTextTableCandidate pdfTextTableCandidate,
      ColumnMatch dateMatch,
      ColumnMatch descriptionMatch,
      PdfWizardAmountMode amountMode,
      PdfWizardColumnMapping pdfWizardColumnMapping) {
    var rejectionReasons = new ArrayList<String>();
    if (pdfTextTableCandidate.rowCount() < MIN_TRANSACTION_ROWS) {
      rejectionReasons.add("Too few data rows were detected in this table candidate.");
    }
    if (dateMatch.score() < MIN_CONFIDENT_CANDIDATE_SCORE) {
      rejectionReasons.add("No confident date column was detected.");
    }
    if (isBlank(pdfWizardColumnMapping.dateFormat())) {
      rejectionReasons.add("No supported date format was inferred for the date column.");
    }
    if (descriptionMatch.score() < MIN_CONFIDENT_CANDIDATE_SCORE) {
      rejectionReasons.add("No confident description column was detected.");
    }
    if (amountMode == null) {
      rejectionReasons.add(
          "No signed amount column or debit/credit amount column pair was detected.");
    } else if (amountMode == PdfWizardAmountMode.SIGNED_AMOUNT
        && pdfWizardColumnMapping.negativeMeans() == null
        && pdfWizardColumnMapping.typeHeader() == null) {
      rejectionReasons.add(
          "Signed amount direction could not be inferred; confirm whether negative amounts are "
              + "credits or debits.");
    }
    return List.copyOf(rejectionReasons);
  }

  private double calculateConfidence(
      PdfTextTableCandidate pdfTextTableCandidate,
      Map<String, Double> columnConfidences,
      PdfWizardAmountMode amountMode,
      PdfWizardColumnMapping pdfWizardColumnMapping) {
    var keys = new ArrayList<>(List.of("dateHeader", "descriptionHeader"));
    if (amountMode == PdfWizardAmountMode.SIGNED_AMOUNT) {
      keys.add("amountHeader");
    } else if (amountMode == PdfWizardAmountMode.DEBIT_CREDIT_COLUMNS) {
      keys.add("debitHeader");
      keys.add("creditHeader");
    } else {
      keys.add("amountHeader");
    }
    var columnScore =
        keys.stream()
            .map(columnConfidences::get)
            .filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
    var confidence =
        (columnScore * 0.7)
            + (rowContinuityScore(pdfTextTableCandidate) * 0.2)
            + (minimumRowsScore(pdfTextTableCandidate) * 0.1);
    if (missingRequiredColumnConfidence(columnConfidences, amountMode, pdfWizardColumnMapping)) {
      confidence = Math.min(confidence, MIN_CONFIDENT_CANDIDATE_SCORE - 0.01);
    }
    return roundToTwoDecimals(confidence);
  }

  private boolean missingRequiredColumnConfidence(
      Map<String, Double> columnConfidences,
      PdfWizardAmountMode amountMode,
      PdfWizardColumnMapping pdfWizardColumnMapping) {
    if (columnConfidences.getOrDefault("dateHeader", 0.0) < MIN_CONFIDENT_CANDIDATE_SCORE
        || columnConfidences.getOrDefault("descriptionHeader", 0.0)
            < MIN_CONFIDENT_CANDIDATE_SCORE) {
      return true;
    }
    if (isBlank(pdfWizardColumnMapping.dateFormat())) {
      return true;
    }
    if (amountMode == PdfWizardAmountMode.SIGNED_AMOUNT) {
      return columnConfidences.getOrDefault("amountHeader", 0.0) < MIN_CONFIDENT_CANDIDATE_SCORE;
    }
    if (amountMode == PdfWizardAmountMode.DEBIT_CREDIT_COLUMNS) {
      return columnConfidences.getOrDefault("debitHeader", 0.0) < MIN_CONFIDENT_CANDIDATE_SCORE
          || columnConfidences.getOrDefault("creditHeader", 0.0) < MIN_CONFIDENT_CANDIDATE_SCORE;
    }
    return true;
  }

  private double rowContinuityScore(PdfTextTableCandidate pdfTextTableCandidate) {
    if (pdfTextTableCandidate.sampleRows().isEmpty()) {
      return 0.0;
    }
    var expectedColumns = pdfTextTableCandidate.headerCells().size();
    var continuousRows =
        pdfTextTableCandidate.sampleRows().stream()
            .filter(row -> Math.abs(row.size() - expectedColumns) <= 1)
            .count();
    return (double) continuousRows / pdfTextTableCandidate.sampleRows().size();
  }

  private double minimumRowsScore(PdfTextTableCandidate pdfTextTableCandidate) {
    return pdfTextTableCandidate.rowCount() >= MIN_TRANSACTION_ROWS ? 1.0 : 0.0;
  }

  private double roundToTwoDecimals(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  private List<String> topLevelRejectionReasons(List<PdfWizardTableCandidate> candidates) {
    if (candidates.isEmpty()) {
      return List.of("No table-like text was found in the PDF sample.");
    }
    if (candidates.getFirst().confidence() < MIN_CONFIDENT_CANDIDATE_SCORE) {
      return List.of("No confident transaction table was found in the PDF sample.");
    }
    return List.of();
  }

  private boolean isDateLike(String value) {
    if (value == null) {
      return false;
    }
    return NUMERIC_DATE_PATTERN.matcher(value).find()
        || YEARLESS_NUMERIC_DATE_PATTERN.matcher(value).find()
        || ISO_DATE_PATTERN.matcher(value).find()
        || MONTH_DATE_PATTERN.matcher(value).find();
  }

  private boolean isAmountLike(String value) {
    if (value == null) {
      return false;
    }
    return AMOUNT_PATTERN.matcher(value.replace(",", "").strip()).matches();
  }

  private boolean isNegativeAmountLike(String value) {
    if (value == null) {
      return false;
    }
    var normalizedValue = value.strip();
    return normalizedValue.startsWith("-") || normalizedValue.startsWith("(");
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private enum HeaderKind {
    DATE,
    DESCRIPTION,
    AMOUNT,
    DEBIT,
    CREDIT,
    TYPE
  }

  private record ColumnMatch(String header, List<String> sampleValues, double score) {

    private String acceptedHeader() {
      return score >= MIN_CONFIDENT_CANDIDATE_SCORE ? header : null;
    }
  }
}
