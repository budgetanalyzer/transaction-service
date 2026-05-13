package org.budgetanalyzer.transaction.service.extractor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

/**
 * Extracts transactions from Bangkok Bank statement PDF files.
 *
 * <p>Format key: {@code bkk-bank-statement-pdf}
 */
@Component
public class BangkokBankStatementPdfExtractor implements StatementExtractor {

  private static final Logger log = LoggerFactory.getLogger(BangkokBankStatementPdfExtractor.class);

  private static final String FORMAT_KEY = "bkk-bank-statement-pdf";
  private static final String BANK_NAME = "Bangkok Bank";
  private static final String CURRENCY_CODE = "THB";
  private static final float LINE_Y_TOLERANCE = 1.5f;
  private static final float COLUMN_X_TOLERANCE = 4f;
  private static final DateTimeFormatter DATE_FORMATTER =
      new DateTimeFormatterBuilder()
          .appendPattern("dd/MM/")
          .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
          .toFormatter(Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern BANK_PATTERN =
      Pattern.compile("\\bBangkok\\s+Bank\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern TABLE_HEADER_PATTERN =
      Pattern.compile(
          "\\bDate\\b\\s+\\bParticulars\\b(?:\\s+\\S+)*?\\s+\\bWithdrawal\\b\\s+\\bDeposit\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern DATE_PREFIX_PATTERN =
      Pattern.compile("^(\\d{2}/\\d{2}/\\d{2})\\s+(.+)$");
  private static final Pattern DATE_VALUE_PATTERN = Pattern.compile("^\\d{2}/\\d{2}/\\d{2}$");
  private static final Pattern BALANCE_FORWARD_PATTERN =
      Pattern.compile("^(?:B/F|BALANCE\\s+FORWARD)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern AMOUNT_PATTERN =
      Pattern.compile("(?<!\\d)[(+-]?(?:THB\\s*)?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}\\)?");

  @Override
  public boolean canHandle(byte[] fileContent, String filename) {
    if (fileContent == null
        || filename == null
        || !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
      return false;
    }

    try {
      var text = extractTextFromPdf(fileContent, 1, 2);
      if (!BANK_PATTERN.matcher(text).find()) {
        return false;
      }
      var lines = extractTextLinesFromPdf(fileContent, 1, 2);
      return containsTransactionTable(lines);
    } catch (Exception exception) {
      log.debug(
          "Failed to check if file is Bangkok Bank Statement PDF: {}", exception.getMessage());
      return false;
    }
  }

  @Override
  public List<PreviewTransaction> extract(byte[] fileContent, String accountId) {
    try {
      var lines = extractTextLinesFromPdf(fileContent, 1, Integer.MAX_VALUE);
      var transactions = parseTransactions(lines, accountId);
      log.info("Extracted {} transactions from Bangkok Bank Statement PDF", transactions.size());
      return transactions;
    } catch (BusinessException businessException) {
      throw businessException;
    } catch (Exception exception) {
      throw new BusinessException(
          "Failed to extract transactions from Bangkok Bank Statement PDF: "
              + exception.getMessage(),
          BudgetAnalyzerError.PDF_PARSING_ERROR.name(),
          exception);
    }
  }

  @Override
  public List<Transaction> extractEntities(
      byte[] fileContent, String accountId, FileImport fileImport) {
    return extract(fileContent, accountId).stream()
        .map(previewTransaction -> toTransaction(previewTransaction, fileImport))
        .toList();
  }

  @Override
  public String getFormatKey() {
    return FORMAT_KEY;
  }

  private String extractTextFromPdf(byte[] fileContent, int startPage, int endPage)
      throws IOException {
    try (PDDocument document = Loader.loadPDF(fileContent)) {
      var pdfTextStripper = new PDFTextStripper();
      pdfTextStripper.setStartPage(startPage);
      pdfTextStripper.setEndPage(Math.min(endPage, document.getNumberOfPages()));
      return pdfTextStripper.getText(document);
    }
  }

  private List<PdfTextLine> extractTextLinesFromPdf(byte[] fileContent, int startPage, int endPage)
      throws IOException {
    try (PDDocument document = Loader.loadPDF(fileContent)) {
      var pdfTextStripper = new PositionAwareTextStripper();
      pdfTextStripper.setSortByPosition(true);
      pdfTextStripper.setStartPage(startPage);
      pdfTextStripper.setEndPage(Math.min(endPage, document.getNumberOfPages()));
      pdfTextStripper.getText(document);
      return groupTextChunksIntoLines(pdfTextStripper.getChunks());
    }
  }

  private List<PdfTextLine> groupTextChunksIntoLines(List<PdfTextChunk> chunks) {
    var sortedChunks =
        chunks.stream()
            .sorted(
                Comparator.comparingInt(PdfTextChunk::page)
                    .thenComparing(PdfTextChunk::y)
                    .thenComparing(PdfTextChunk::x))
            .toList();
    var lines = new ArrayList<PdfTextLine>();

    for (var chunk : sortedChunks) {
      var line = findMatchingLine(lines, chunk);
      if (line == null) {
        line = new PdfTextLine(chunk.page(), chunk.y(), new ArrayList<>());
        lines.add(line);
      }
      line.chunks().add(chunk);
    }

    lines.forEach(line -> line.chunks().sort(Comparator.comparing(PdfTextChunk::x)));
    return lines;
  }

  private PdfTextLine findMatchingLine(List<PdfTextLine> lines, PdfTextChunk chunk) {
    for (var lineIndex = lines.size() - 1; lineIndex >= 0; lineIndex--) {
      var line = lines.get(lineIndex);
      if (line.page() == chunk.page() && Math.abs(line.y() - chunk.y()) <= LINE_Y_TOLERANCE) {
        return line;
      }
      if (line.page() != chunk.page()) {
        break;
      }
    }
    return null;
  }

  private List<PreviewTransaction> parseTransactions(List<PdfTextLine> lines, String accountId) {
    var transactions = new ArrayList<PreviewTransaction>();
    TableColumns tableColumns = null;

    for (var textLine : lines) {
      var line = textLine.text();
      if (line.isBlank()) {
        continue;
      }

      var headerColumns = parseTableHeader(textLine);
      if (headerColumns != null) {
        tableColumns = headerColumns;
        continue;
      }

      if (tableColumns == null) {
        continue;
      }

      var previewTransaction = parseTransactionLine(textLine, tableColumns, accountId);
      if (previewTransaction != null) {
        transactions.add(previewTransaction);
      }
    }

    return transactions;
  }

  private boolean containsTransactionTable(List<PdfTextLine> lines) {
    var foundHeader = false;
    for (var textLine : lines) {
      if (parseTableHeader(textLine) != null) {
        foundHeader = true;
        continue;
      }
      if (foundHeader
          && (findDateChunk(textLine) != null
              || DATE_PREFIX_PATTERN.matcher(textLine.text()).find())) {
        return true;
      }
    }
    return false;
  }

  private TableColumns parseTableHeader(PdfTextLine textLine) {
    var positionedColumns = parsePositionedTableHeader(textLine);
    if (positionedColumns != null) {
      return positionedColumns;
    }

    var line = textLine.text();
    var headerMatcher = TABLE_HEADER_PATTERN.matcher(line);
    if (!headerMatcher.find()) {
      return null;
    }

    var lowerCaseLine = line.toLowerCase(Locale.ROOT);
    var depositStart = lowerCaseLine.indexOf("deposit", headerMatcher.start());
    var balanceStart = lowerCaseLine.indexOf("balance", depositStart);
    return new TableColumns(
        lowerCaseLine.indexOf("withdrawal", headerMatcher.start()),
        depositStart,
        balanceStart >= 0 ? balanceStart : Integer.MAX_VALUE,
        null,
        null,
        null);
  }

  private TableColumns parsePositionedTableHeader(PdfTextLine textLine) {
    PdfTextChunk dateChunk = null;
    PdfTextChunk particularsChunk = null;
    PdfTextChunk withdrawalChunk = null;
    PdfTextChunk depositChunk = null;
    PdfTextChunk balanceChunk = null;

    for (var chunk : textLine.chunks()) {
      if ("date".equalsIgnoreCase(chunk.text())) {
        dateChunk = chunk;
      } else if ("particulars".equalsIgnoreCase(chunk.text())) {
        particularsChunk = chunk;
      } else if ("withdrawal".equalsIgnoreCase(chunk.text())) {
        withdrawalChunk = chunk;
      } else if ("deposit".equalsIgnoreCase(chunk.text())) {
        depositChunk = chunk;
      } else if ("balance".equalsIgnoreCase(chunk.text())) {
        balanceChunk = chunk;
      }
    }

    if (dateChunk == null
        || particularsChunk == null
        || withdrawalChunk == null
        || depositChunk == null) {
      return null;
    }

    var depositEndX =
        balanceChunk != null && balanceChunk.x() > depositChunk.x() ? balanceChunk.x() : null;
    return new TableColumns(
        0, 0, Integer.MAX_VALUE, withdrawalChunk.x(), depositChunk.x(), depositEndX);
  }

  private PreviewTransaction parseTransactionLine(
      PdfTextLine textLine, TableColumns tableColumns, String accountId) {
    if (tableColumns.hasPositionedColumns()) {
      var positionedTransaction = parsePositionedTransactionLine(textLine, tableColumns, accountId);
      if (positionedTransaction != null) {
        return positionedTransaction;
      }
    }

    return parseTextTransactionLine(textLine.text(), tableColumns, accountId);
  }

  private PreviewTransaction parsePositionedTransactionLine(
      PdfTextLine textLine, TableColumns tableColumns, String accountId) {
    var dateChunk = findDateChunk(textLine);
    if (dateChunk == null) {
      return null;
    }

    var description = descriptionText(textLine, dateChunk, tableColumns);
    if (isBalanceForwardDescription(description)) {
      return null;
    }

    var withdrawalText = columnText(textLine, tableColumns.withdrawalX(), tableColumns.depositX());
    var depositText =
        columnText(textLine, tableColumns.depositX(), tableColumns.depositEndBoundaryX());
    var hasWithdrawal = !withdrawalText.isBlank();
    var hasDeposit = !depositText.isBlank();

    if (!hasWithdrawal && !hasDeposit) {
      throw parsingError("Missing amount value in Bangkok Bank statement row: " + textLine.text());
    }
    if (hasWithdrawal && hasDeposit) {
      throw parsingError(
          "Both withdrawal and deposit are populated in Bangkok Bank row: " + textLine.text());
    }

    if (description.isBlank()) {
      throw parsingError("Missing description in Bangkok Bank statement row: " + textLine.text());
    }

    var rawAmount = hasDeposit ? depositText : withdrawalText;
    var transactionType = hasDeposit ? TransactionType.CREDIT : TransactionType.DEBIT;

    return new PreviewTransaction(
        parseDate(dateChunk.text(), textLine.text()),
        description,
        parseAmount(rawAmount, textLine.text()),
        transactionType,
        null,
        BANK_NAME,
        CURRENCY_CODE,
        accountId);
  }

  private PdfTextChunk findDateChunk(PdfTextLine textLine) {
    return textLine.chunks().stream()
        .filter(chunk -> DATE_VALUE_PATTERN.matcher(chunk.text()).matches())
        .findFirst()
        .orElse(null);
  }

  private String columnText(PdfTextLine textLine, float startX, float endX) {
    return textLine.chunks().stream()
        .filter(chunk -> chunk.centerX() >= startX - COLUMN_X_TOLERANCE)
        .filter(chunk -> chunk.centerX() < endX - COLUMN_X_TOLERANCE)
        .map(PdfTextChunk::text)
        .reduce((left, right) -> left + " " + right)
        .orElse("");
  }

  private String descriptionText(
      PdfTextLine textLine, PdfTextChunk dateChunk, TableColumns tableColumns) {
    return textLine.chunks().stream()
        .filter(chunk -> chunk != dateChunk)
        .filter(chunk -> chunk.x() < tableColumns.withdrawalX() - COLUMN_X_TOLERANCE)
        .map(PdfTextChunk::text)
        .reduce((left, right) -> left + " " + right)
        .orElse("")
        .trim();
  }

  private PreviewTransaction parseTextTransactionLine(
      String line, TableColumns tableColumns, String accountId) {
    var dateMatcher = DATE_PREFIX_PATTERN.matcher(line);
    if (!dateMatcher.find()) {
      return null;
    }

    var remainder = dateMatcher.group(2);
    var descriptionWithoutBalanceAmount = descriptionWithoutBalanceAmount(remainder);
    if (isBalanceForwardDescription(descriptionWithoutBalanceAmount)) {
      return null;
    }

    var remainderStart = dateMatcher.start(2);
    var amountMatches = findColumnAmountMatches(remainder, remainderStart, tableColumns);
    if (amountMatches.isEmpty()) {
      throw parsingError("Missing amount value in Bangkok Bank statement row: " + line);
    }
    if (amountMatches.size() > 1) {
      throw parsingError("Both withdrawal and deposit are populated in Bangkok Bank row: " + line);
    }

    var amountMatch = amountMatches.getFirst();
    var description = remainder.substring(0, amountMatch.start()).trim();
    if (description.isBlank()) {
      throw parsingError("Missing description in Bangkok Bank statement row: " + line);
    }

    return new PreviewTransaction(
        parseDate(dateMatcher.group(1), line),
        description,
        parseAmount(amountMatch.group(), line),
        resolveTransactionType(amountMatch, remainderStart, tableColumns),
        null,
        BANK_NAME,
        CURRENCY_CODE,
        accountId);
  }

  private List<MatchResultSnapshot> findColumnAmountMatches(
      String remainder, int remainderStart, TableColumns tableColumns) {
    var amountMatcher = AMOUNT_PATTERN.matcher(remainder);
    var amountMatches = new ArrayList<MatchResultSnapshot>();
    var transactionColumnStart = Math.max(0, tableColumns.withdrawalStart() - remainderStart);

    while (amountMatcher.find()) {
      var absoluteAmountStart = amountMatcher.start() + remainderStart;
      if (amountMatcher.start() >= transactionColumnStart
          && absoluteAmountStart < tableColumns.depositEnd()) {
        amountMatches.add(
            new MatchResultSnapshot(
                amountMatcher.start(), amountMatcher.end(), amountMatcher.group()));
      }
    }

    return amountMatches;
  }

  private TransactionType resolveTransactionType(
      MatchResultSnapshot amountMatch, int remainderStart, TableColumns tableColumns) {
    var absoluteAmountStart = amountMatch.start() + remainderStart;
    if (absoluteAmountStart >= tableColumns.depositStart()) {
      return TransactionType.CREDIT;
    }
    return TransactionType.DEBIT;
  }

  private LocalDate parseDate(String rawDate, String line) {
    try {
      return LocalDate.from(DATE_FORMATTER.parse(rawDate));
    } catch (DateTimeParseException dateTimeParseException) {
      throw parsingError("Invalid date value in Bangkok Bank statement row: " + line);
    }
  }

  private BigDecimal parseAmount(String rawAmount, String line) {
    try {
      var cleaned = rawAmount.replaceAll("[^0-9.]", "");
      if (cleaned.isBlank()) {
        throw new NumberFormatException("Blank amount");
      }
      return new BigDecimal(cleaned);
    } catch (NumberFormatException numberFormatException) {
      throw parsingError("Invalid amount value in Bangkok Bank statement row: " + line);
    }
  }

  private boolean isBalanceForwardDescription(String description) {
    return BALANCE_FORWARD_PATTERN.matcher(description.strip()).matches();
  }

  private String descriptionWithoutBalanceAmount(String remainder) {
    return AMOUNT_PATTERN.matcher(remainder).replaceAll("").strip();
  }

  private Transaction toTransaction(PreviewTransaction previewTransaction, FileImport fileImport) {
    var transaction = new Transaction();
    transaction.setDate(previewTransaction.date());
    transaction.setDescription(previewTransaction.description());
    transaction.setAmount(previewTransaction.amount());
    transaction.setType(previewTransaction.type());
    transaction.setBankName(previewTransaction.bankName());
    transaction.setCurrencyIsoCode(previewTransaction.currencyIsoCode());
    transaction.setAccountId(previewTransaction.accountId());
    transaction.setFileImport(fileImport);
    return transaction;
  }

  private BusinessException parsingError(String message) {
    return new BusinessException(message, BudgetAnalyzerError.PDF_PARSING_ERROR.name());
  }

  private record TableColumns(
      int withdrawalStart,
      int depositStart,
      int depositEnd,
      Float withdrawalX,
      Float depositX,
      Float depositEndX) {
    boolean hasPositionedColumns() {
      return withdrawalX != null && depositX != null;
    }

    float depositEndBoundaryX() {
      return depositEndX != null ? depositEndX : Float.MAX_VALUE;
    }
  }

  private record MatchResultSnapshot(int start, int end, String group) {}

  private record PdfTextChunk(int page, float x, float endX, float y, String text) {
    float centerX() {
      return (x + endX) / 2;
    }
  }

  private record PdfTextLine(int page, float y, List<PdfTextChunk> chunks) {
    String text() {
      return chunks.stream()
          .map(PdfTextChunk::text)
          .reduce((left, right) -> left + " " + right)
          .orElse("")
          .strip();
    }
  }

  private static class PositionAwareTextStripper extends PDFTextStripper {

    private final List<PdfTextChunk> chunks = new ArrayList<>();
    private int currentPage;

    PositionAwareTextStripper() throws IOException {}

    List<PdfTextChunk> getChunks() {
      return chunks;
    }

    @Override
    protected void startPage(PDPage page) throws IOException {
      currentPage++;
      super.startPage(page);
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
      var normalizedText = text.strip();
      if (normalizedText.isBlank() || textPositions.isEmpty()) {
        return;
      }

      var firstTextPosition = textPositions.getFirst();
      var lastTextPosition = textPositions.getLast();
      chunks.add(
          new PdfTextChunk(
              currentPage,
              firstTextPosition.getXDirAdj(),
              lastTextPosition.getXDirAdj() + lastTextPosition.getWidthDirAdj(),
              firstTextPosition.getYDirAdj(),
              normalizedText));
    }
  }
}
