package org.budgetanalyzer.transaction.service.extractor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;
import org.budgetanalyzer.transaction.service.dto.ParserAttempt;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableParserConfig;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableYearSource;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextDocument;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextExtractionService;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextLine;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextTableCandidate;

/**
 * Configurable text-PDF table extractor backed by a deterministic parser revision configuration.
 */
public class ConfigurablePdfTextTableStatementExtractor implements StatementExtractor {

  private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");

  private final StatementFormat statementFormat;
  private final ParserRevision parserRevision;
  private final PdfTextTableParserConfig pdfTextTableParserConfig;
  private final PdfTextExtractionService pdfTextExtractionService;

  /**
   * Constructs a configurable PDF text-table statement extractor.
   *
   * @param statementFormat statement format owning the parser revision
   * @param parserRevision parser revision containing deterministic parser configuration
   * @param pdfTextTableParserConfig parsed PDF text-table parser configuration
   * @param pdfTextExtractionService text-PDF extraction service
   */
  public ConfigurablePdfTextTableStatementExtractor(
      StatementFormat statementFormat,
      ParserRevision parserRevision,
      PdfTextTableParserConfig pdfTextTableParserConfig,
      PdfTextExtractionService pdfTextExtractionService) {
    this.statementFormat = statementFormat;
    this.parserRevision = parserRevision;
    this.pdfTextTableParserConfig = pdfTextTableParserConfig;
    this.pdfTextExtractionService = pdfTextExtractionService;
  }

  @Override
  public ParserAttempt attempt(
      ParserRevision parserRevision, byte[] fileContent, String filename, String accountId) {
    if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
      return ParserAttempt.notApplicable(parserRevision);
    }

    PdfTextDocument pdfTextDocument;
    try {
      pdfTextDocument = pdfTextExtractionService.extract(fileContent, filename);
    } catch (BusinessException businessException) {
      return ParserAttempt.notApplicable(parserRevision);
    } catch (Exception exception) {
      return ParserAttempt.notApplicable(parserRevision);
    }

    var pdfTextTableCandidates = selectCandidates(pdfTextDocument);
    if (pdfTextTableCandidates.isEmpty()) {
      return ParserAttempt.notApplicable(parserRevision);
    }

    try {
      var transactions = parseRows(pdfTextDocument, pdfTextTableCandidates, accountId);
      if (transactions.isEmpty()) {
        return ParserAttempt.notApplicable(parserRevision);
      }
      return ParserAttempt.matched(parserRevision, transactions);
    } catch (BusinessException businessException) {
      return ParserAttempt.failed(parserRevision, businessException);
    } catch (Exception exception) {
      return ParserAttempt.failed(parserRevision, pdfParsingError(exception));
    }
  }

  @Override
  public String getHandlerKey() {
    return "statement-format-"
        + statementFormat.getId()
        + "-revision-"
        + parserRevision.getId()
        + "-pdf-text-table";
  }

  private List<PdfTextTableCandidate> selectCandidates(PdfTextDocument pdfTextDocument) {
    var pdfTextTableCandidates =
        pdfTextDocument.tableCandidates().stream()
            .filter(this::matchesHeaderRules)
            .filter(this::hasMappedHeaders)
            .sorted(
                Comparator.comparing(PdfTextTableCandidate::pageNumber)
                    .thenComparing(PdfTextTableCandidate::startLineNumber))
            .toList();
    var rowCount = pdfTextTableCandidates.stream().mapToInt(PdfTextTableCandidate::rowCount).sum();
    if (rowCount < pdfTextTableParserConfig.minimumRows()) {
      return List.of();
    }
    return pdfTextTableCandidates;
  }

  private boolean matchesHeaderRules(PdfTextTableCandidate pdfTextTableCandidate) {
    var normalizedHeaders =
        pdfTextTableCandidate.headerCells().stream().map(this::normalize).toList();
    return pdfTextTableParserConfig.headerMustContain().stream()
        .map(this::normalize)
        .allMatch(
            expectedHeader ->
                normalizedHeaders.stream().anyMatch(header -> header.contains(expectedHeader)));
  }

  private boolean hasMappedHeaders(PdfTextTableCandidate pdfTextTableCandidate) {
    var headerIndexes = headerIndexes(pdfTextTableCandidate);
    if (!headerIndexes.containsKey(normalize(pdfTextTableParserConfig.dateHeader()))
        || !headerIndexes.containsKey(normalize(pdfTextTableParserConfig.descriptionHeader()))) {
      return false;
    }
    if (!isBlank(pdfTextTableParserConfig.typeHeader())
        && !headerIndexes.containsKey(normalize(pdfTextTableParserConfig.typeHeader()))) {
      return false;
    }
    if (hasSignedAmountColumn()) {
      return headerIndexes.containsKey(normalize(pdfTextTableParserConfig.amountHeader()));
    }
    return headerIndexes.containsKey(normalize(pdfTextTableParserConfig.debitHeader()))
        && headerIndexes.containsKey(normalize(pdfTextTableParserConfig.creditHeader()));
  }

  private List<PreviewTransaction> parseRows(
      PdfTextDocument pdfTextDocument,
      List<PdfTextTableCandidate> pdfTextTableCandidates,
      String accountId) {
    var statementYear = resolveStatementYear(pdfTextDocument);
    var transactions = new ArrayList<PreviewTransaction>();
    for (var pdfTextTableCandidate : pdfTextTableCandidates) {
      var headerIndexes = headerIndexes(pdfTextTableCandidate);
      pdfTextTableCandidate.dataRows().stream()
          .map(row -> parseRow(row, headerIndexes, statementYear, accountId))
          .forEach(transactions::add);
    }
    return List.copyOf(transactions);
  }

  private PreviewTransaction parseRow(
      List<String> row,
      Map<String, Integer> headerIndexes,
      Integer statementYear,
      String accountId) {
    var date =
        parseDate(value(row, headerIndexes, pdfTextTableParserConfig.dateHeader()), statementYear);
    var description = value(row, headerIndexes, pdfTextTableParserConfig.descriptionHeader());
    if (description.isBlank()) {
      throw new BusinessException(
          "PDF text-table row has a blank transaction description.",
          BudgetAnalyzerError.PDF_PARSING_ERROR.name());
    }
    var typeAndAmount = parseTypeAndAmount(row, headerIndexes);
    return new PreviewTransaction(
        date,
        description,
        typeAndAmount.amount(),
        typeAndAmount.type(),
        null,
        statementFormat.getBankName(),
        statementFormat.getDefaultCurrencyIsoCode(),
        accountId);
  }

  private LocalDate parseDate(String rawDate, Integer statementYear) {
    if (rawDate.isBlank()) {
      throw new BusinessException(
          "PDF text-table row has a blank transaction date.",
          BudgetAnalyzerError.PDF_PARSING_ERROR.name());
    }
    var dateFormat = pdfTextTableParserConfig.dateFormat();
    try {
      if (dateFormatContainsYear(dateFormat)) {
        return LocalDate.from(buildDateFormatter(dateFormat).parse(rawDate));
      }
      if (statementYear == null) {
        throw new BusinessException(
            "PDF text-table date format omits a year, but no statement year was found.",
            BudgetAnalyzerError.PDF_PARSING_ERROR.name());
      }
      return LocalDate.from(
          buildDateFormatter(dateFormat + " uuuu").parse(rawDate + " " + statementYear));
    } catch (DateTimeParseException dateTimeParseException) {
      throw new BusinessException(
          "Invalid PDF text-table date value: " + rawDate,
          BudgetAnalyzerError.PDF_PARSING_ERROR.name(),
          dateTimeParseException);
    }
  }

  private TypeAndAmount parseTypeAndAmount(List<String> row, Map<String, Integer> headerIndexes) {
    if (hasSignedAmountColumn()) {
      var rawAmount = value(row, headerIndexes, pdfTextTableParserConfig.amountHeader());
      var signedAmount = parseSignedAmount(rawAmount);
      var transactionType = parseSignedAmountType(row, headerIndexes, signedAmount);
      return new TypeAndAmount(transactionType, signedAmount.abs());
    }

    var debitAmount = value(row, headerIndexes, pdfTextTableParserConfig.debitHeader());
    var creditAmount = value(row, headerIndexes, pdfTextTableParserConfig.creditHeader());
    var hasDebitAmount = !debitAmount.isBlank();
    var hasCreditAmount = !creditAmount.isBlank();
    if (hasDebitAmount == hasCreditAmount) {
      throw new BusinessException(
          "PDF text-table row must contain exactly one debit or credit amount.",
          BudgetAnalyzerError.PDF_PARSING_ERROR.name());
    }
    if (hasDebitAmount) {
      return new TypeAndAmount(TransactionType.DEBIT, parseSignedAmount(debitAmount).abs());
    }
    return new TypeAndAmount(TransactionType.CREDIT, parseSignedAmount(creditAmount).abs());
  }

  private TransactionType parseSignedAmountType(
      List<String> row, Map<String, Integer> headerIndexes, BigDecimal signedAmount) {
    if (pdfTextTableParserConfig.typeHeader() != null
        && !pdfTextTableParserConfig.typeHeader().isBlank()) {
      var typeValue = value(row, headerIndexes, pdfTextTableParserConfig.typeHeader());
      var transactionType = parseTransactionType(typeValue);
      if (transactionType != null) {
        return transactionType;
      }
    }

    var negativeAmount = signedAmount.signum() < 0;
    if (pdfTextTableParserConfig.negativeMeans() == null) {
      throw new BusinessException(
          "PDF text-table row has an unrecognized transaction type value.",
          BudgetAnalyzerError.PDF_PARSING_ERROR.name());
    }
    if (negativeAmount) {
      return pdfTextTableParserConfig.negativeMeans() == PdfTextTableNegativeMeans.CREDIT
          ? TransactionType.CREDIT
          : TransactionType.DEBIT;
    }
    return pdfTextTableParserConfig.negativeMeans() == PdfTextTableNegativeMeans.CREDIT
        ? TransactionType.DEBIT
        : TransactionType.CREDIT;
  }

  private TransactionType parseTransactionType(String value) {
    var normalizedValue = normalize(value);
    if (List.of("credit", "cr", "deposit", "payment", "inflow").contains(normalizedValue)) {
      return TransactionType.CREDIT;
    }
    if (List.of("debit", "dr", "withdrawal", "withdraw", "purchase", "outflow")
        .contains(normalizedValue)) {
      return TransactionType.DEBIT;
    }
    return null;
  }

  private BigDecimal parseSignedAmount(String rawAmount) {
    if (rawAmount.isBlank()) {
      throw new BusinessException(
          "PDF text-table row has a blank transaction amount.",
          BudgetAnalyzerError.PDF_PARSING_ERROR.name());
    }
    var normalizedAmount = rawAmount.strip();
    var negative = normalizedAmount.startsWith("(") || normalizedAmount.startsWith("-");
    normalizedAmount =
        normalizedAmount
            .replace("(", "")
            .replace(")", "")
            .replace("$", "")
            .replace(",", "")
            .replace("+", "")
            .replace("-", "")
            .strip();
    try {
      var amount = new BigDecimal(normalizedAmount);
      return negative ? amount.negate() : amount;
    } catch (NumberFormatException numberFormatException) {
      throw new BusinessException(
          "Invalid PDF text-table amount value: " + rawAmount,
          BudgetAnalyzerError.PDF_PARSING_ERROR.name(),
          numberFormatException);
    }
  }

  private Integer resolveStatementYear(PdfTextDocument pdfTextDocument) {
    if (dateFormatContainsYear(pdfTextTableParserConfig.dateFormat())) {
      return null;
    }
    if (pdfTextTableParserConfig.yearSource() != PdfTextTableYearSource.STATEMENT_PERIOD) {
      return null;
    }
    return pdfTextDocument.pages().stream()
        .flatMap(pdfTextPage -> pdfTextPage.lines().stream())
        .map(PdfTextLine::text)
        .map(YEAR_PATTERN::matcher)
        .filter(java.util.regex.Matcher::find)
        .map(java.util.regex.Matcher::group)
        .map(Integer::valueOf)
        .findFirst()
        .orElse(null);
  }

  private Map<String, Integer> headerIndexes(PdfTextTableCandidate pdfTextTableCandidate) {
    var headerIndexes = new HashMap<String, Integer>();
    var headers = pdfTextTableCandidate.headerCells();
    for (var index = 0; index < headers.size(); index++) {
      headerIndexes.put(normalize(headers.get(index)), index);
    }
    return Map.copyOf(headerIndexes);
  }

  private String value(List<String> row, Map<String, Integer> headerIndexes, String header) {
    var index = headerIndexes.get(normalize(header));
    // selectCandidates proves mapped headers before parsing; remaining checks cover malformed rows.
    if (index >= row.size() || row.get(index) == null) {
      return "";
    }
    return row.get(index).strip();
  }

  private DateTimeFormatter buildDateFormatter(String dateFormat) {
    return DateTimeFormatter.ofPattern(dateFormat, Locale.ENGLISH)
        .withResolverStyle(ResolverStyle.SMART);
  }

  private boolean dateFormatContainsYear(String dateFormat) {
    return dateFormat.contains("u") || dateFormat.contains("y");
  }

  private boolean hasSignedAmountColumn() {
    return pdfTextTableParserConfig.amountHeader() != null
        && !pdfTextTableParserConfig.amountHeader().isBlank();
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  private BusinessException pdfParsingError(Exception exception) {
    return new BusinessException(
        "Failed to parse PDF text table: " + exception.getMessage(),
        BudgetAnalyzerError.PDF_PARSING_ERROR.name(),
        exception);
  }

  private record TypeAndAmount(TransactionType type, BigDecimal amount) {}
}
