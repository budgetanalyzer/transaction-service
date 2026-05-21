package org.budgetanalyzer.transaction.service.extractor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
 * Extracts transactions from Capital One Credit Card Monthly Statements (PDF).
 *
 * <p>Handles Capital One credit card monthly billing statements (VentureOne, Quicksilver, etc.).
 * Parses transaction tables with columns: Trans Date, Post Date, Description, Amount.
 *
 * <p>Format key: {@code capital-one-credit-monthly-statement}
 */
@Component
public class CapitalOneCreditMonthlyStatementExtractor implements StatementExtractor {

  private static final Logger log =
      LoggerFactory.getLogger(CapitalOneCreditMonthlyStatementExtractor.class);

  private static final String FORMAT_KEY = "capital-one-credit-monthly-statement";
  private static final String BANK_NAME = "Capital One";
  private static final String CURRENCY_CODE = "USD";

  // Pattern to detect Capital One credit card monthly statement
  // Looks for "Credit Card" and billing cycle pattern (DOTALL needed for multiline matching)
  private static final Pattern CREDIT_CARD_STATEMENT_PATTERN =
      Pattern.compile(
          "Credit Card.*\\d+ days in Billing Cycle", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  // Pattern to extract statement period: "Nov 19, 2025 - Dec 19, 2025"
  private static final Pattern STATEMENT_PERIOD_PATTERN =
      Pattern.compile(
          "(\\w{3})\\s+(\\d{1,2}),\\s+(\\d{4})\\s*-\\s*(\\w{3})\\s+(\\d{1,2}),\\s+(\\d{4})");

  // Pattern to match transaction lines: "Nov 18 Nov 19 DESCRIPTION $9.97"
  // Trans Date (MMM DD) + Post Date (MMM DD) + Description + Amount
  private static final Pattern TRANSACTION_PATTERN =
      Pattern.compile(
          "^(\\w{3})\\s+(\\d{1,2})\\s+(\\w{3})\\s+(\\d{1,2})\\s+"
              + "(.+?)\\s+(-\\s*)?\\$([\\d,]+\\.\\d{2})\\s*$");

  // Section headers
  private static final Pattern PAYMENTS_SECTION_PATTERN =
      Pattern.compile("Payments, Credits and Adjustments", Pattern.CASE_INSENSITIVE);

  private static final Pattern TRANSACTIONS_SECTION_PATTERN =
      Pattern.compile(
          "^\\s*Transactions(?: \\(Continued\\))?\\s*$|:#\\d+:\\s*Transactions",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern TRANSACTION_TABLE_HEADER_PATTERN =
      Pattern.compile(
          "Trans Date\\s+Post Date\\s+Description\\s+Amount",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final Pattern SPLIT_COLUMN_DATE_PATTERN =
      Pattern.compile("^(\\w{3})\\s+(\\d{1,2})$");

  private static final Pattern SPLIT_COLUMN_AMOUNT_PATTERN =
      Pattern.compile("^(-\\s*)?\\$([\\d,]+\\.\\d{2})$");

  private static final Pattern SPLIT_COLUMN_DETAIL_PATTERN =
      Pattern.compile(
          "^([\\d,]+\\.\\d{2}\\s+)?(THB|HKD)\\b.*|"
              + "^Exchange Rate\\b.*|"
              + "^\\d+(\\.\\d+)?\\s+USD\\b.*",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern SPLIT_COLUMN_STOP_PATTERN =
      Pattern.compile(
          "^(Fees|Interest Charge|Interest Charged|Year-to-Date Totals)\\b.*",
          Pattern.CASE_INSENSITIVE);

  // Lines to skip
  private static final Pattern SKIP_PATTERN =
      Pattern.compile(
          "^(Trans Date|Post Date|Description|Amount|"
              + "Page \\d+|Additional Information|"
              + "Total Transactions|Total Fees|Total Interest|"
              + "Transactions \\(Continued\\)|"
              + "Visit capitalone|"
              + "\\$[\\d,]+\\.\\d{2}$|" // Standalone amounts (subtotals)
              + "THB|HKD|Exchange Rate|" // Foreign currency info lines
              + "TK#:|ORIG:|DEST:|PSGR:|S/O:|CARRIER:|SVC:).*", // Airline ticket details
          Pattern.CASE_INSENSITIVE);

  @Override
  public boolean canHandle(byte[] fileContent, String filename) {
    if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
      return false;
    }

    try {
      String text = extractTextFromPdf(fileContent, 1, 2);
      return CREDIT_CARD_STATEMENT_PATTERN.matcher(text).find()
          && text.toLowerCase().contains("capital one");
    } catch (Exception e) {
      log.debug(
          "Failed to check if file is Capital One Credit Monthly Statement: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public List<PreviewTransaction> extract(byte[] fileContent, String accountId) {
    try {
      String fullText = extractTextFromPdf(fileContent, 1, Integer.MAX_VALUE);

      StatementPeriod period = extractStatementPeriod(fullText);
      log.info(
          "Extracting Capital One Credit Monthly Statement for {} {} - {} {}",
          period.startMonth(),
          period.startYear(),
          period.endMonth(),
          period.endYear());

      List<PreviewTransaction> transactions = parseTransactions(fullText, period, accountId);
      log.info(
          "Extracted {} transactions from Capital One Credit Monthly Statement",
          transactions.size());

      return transactions;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(
          "Failed to extract transactions from PDF: " + e.getMessage(),
          BudgetAnalyzerError.PDF_PARSING_ERROR.name(),
          e);
    }
  }

  @Override
  public String getFormatKey() {
    return FORMAT_KEY;
  }

  @Override
  public List<Transaction> extractEntities(
      byte[] fileContent, String accountId, FileImport fileImport) {
    return extract(fileContent, accountId).stream()
        .map(preview -> toTransaction(preview, fileImport))
        .toList();
  }

  private Transaction toTransaction(PreviewTransaction preview, FileImport fileImport) {
    var transaction = new Transaction();
    transaction.setDate(preview.date());
    transaction.setDescription(preview.description());
    transaction.setAmount(preview.amount());
    transaction.setType(preview.type());
    transaction.setBankName(preview.bankName());
    transaction.setCurrencyIsoCode(preview.currencyIsoCode());
    transaction.setAccountId(preview.accountId());
    transaction.setFileImport(fileImport);
    return transaction;
  }

  private String extractTextFromPdf(byte[] fileContent, int startPage, int endPage)
      throws IOException {
    try (PDDocument document = Loader.loadPDF(fileContent)) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setStartPage(startPage);
      stripper.setEndPage(Math.min(endPage, document.getNumberOfPages()));
      return stripper.getText(document);
    }
  }

  private StatementPeriod extractStatementPeriod(String text) {
    Matcher matcher = STATEMENT_PERIOD_PATTERN.matcher(text);
    if (matcher.find()) {
      Month startMonth = parseMonth(matcher.group(1));
      int startDay = Integer.parseInt(matcher.group(2));
      int startYear = Integer.parseInt(matcher.group(3));
      Month endMonth = parseMonth(matcher.group(4));
      int endDay = Integer.parseInt(matcher.group(5));
      int endYear = Integer.parseInt(matcher.group(6));
      return new StatementPeriod(startMonth, startDay, startYear, endMonth, endDay, endYear);
    }
    throw new BusinessException(
        "Could not determine statement period from Capital One Credit Monthly Statement PDF",
        BudgetAnalyzerError.PDF_PARSING_ERROR.name());
  }

  private Month parseMonth(String monthStr) {
    return Month.from(
        DateTimeFormatter.ofPattern("MMM", Locale.US).parse(monthStr.substring(0, 3)));
  }

  private List<PreviewTransaction> parseTransactions(
      String text, StatementPeriod period, String accountId) {
    var transactions = parseSingleLineTransactions(text, period, accountId);
    if (!transactions.isEmpty()) {
      log.debug("Parsed Capital One Credit Monthly Statement with single-line parser");
      return transactions;
    }

    log.debug("Single-line parser returned no rows; trying split-column parser");
    transactions = parseSplitColumnTransactions(text, period, accountId);
    if (!transactions.isEmpty()) {
      return transactions;
    }

    if (containsTransactionTable(text)) {
      throw new BusinessException(
          "Could not parse transaction rows from Capital One Credit Monthly Statement PDF",
          BudgetAnalyzerError.PDF_PARSING_ERROR.name());
    }

    return transactions;
  }

  private List<PreviewTransaction> parseSingleLineTransactions(
      String text, StatementPeriod period, String accountId) {
    List<PreviewTransaction> transactions = new ArrayList<>();
    boolean inPaymentsSection = false;
    boolean inTransactionsSection = false;

    String[] lines = text.split("\\r?\\n");

    for (String line : lines) {
      line = line.trim();
      if (line.isEmpty()) {
        continue;
      }

      // Check for section headers
      if (PAYMENTS_SECTION_PATTERN.matcher(line).find()) {
        inPaymentsSection = true;
        inTransactionsSection = false;
        log.debug("Entered Payments section");
        continue;
      }

      if (TRANSACTIONS_SECTION_PATTERN.matcher(line).find() || line.endsWith(": Transactions")) {
        inPaymentsSection = false;
        inTransactionsSection = true;
        log.debug("Entered Transactions section");
        continue;
      }

      // Skip non-transaction lines
      if (SKIP_PATTERN.matcher(line).find()) {
        continue;
      }

      // Only parse if we're in a transaction section
      if (!inPaymentsSection && !inTransactionsSection) {
        continue;
      }

      // Try to parse as transaction
      PreviewTransaction transaction =
          parseTransactionLine(line, period, accountId, inPaymentsSection);
      if (transaction != null) {
        transactions.add(transaction);
      }
    }

    return transactions;
  }

  private List<PreviewTransaction> parseSplitColumnTransactions(
      String text, StatementPeriod period, String accountId) {
    var transactions = new ArrayList<PreviewTransaction>();
    var section = SplitColumnSection.NONE;
    SplitColumnPendingTransaction pendingTransaction = null;

    String[] lines = text.split("\\r?\\n");
    for (String rawLine : lines) {
      var line = normalizeLine(rawLine);
      if (line.isEmpty()) {
        continue;
      }

      if (PAYMENTS_SECTION_PATTERN.matcher(line).find()) {
        section = SplitColumnSection.PAYMENTS;
        pendingTransaction = null;
        log.debug("Entered split-column Payments section");
        continue;
      }

      if (TRANSACTIONS_SECTION_PATTERN.matcher(line).find() || line.endsWith(": Transactions")) {
        section = SplitColumnSection.TRANSACTIONS;
        pendingTransaction = null;
        log.debug("Entered split-column Transactions section");
        continue;
      }

      if (section == SplitColumnSection.NONE) {
        continue;
      }

      if (isSplitColumnAmountLine(line)) {
        if (pendingTransaction != null && pendingTransaction.isReadyForAmount()) {
          transactions.add(toPreviewTransaction(line, pendingTransaction, period, accountId));
          pendingTransaction = null;
        }
        continue;
      }

      if (shouldStopSplitColumnParsing(line, pendingTransaction)) {
        break;
      }

      if (shouldSkipSplitColumnLine(line)) {
        continue;
      }

      var dateMatcher = SPLIT_COLUMN_DATE_PATTERN.matcher(line);
      if (dateMatcher.matches()) {
        if (pendingTransaction == null) {
          pendingTransaction =
              new SplitColumnPendingTransaction(
                  dateMatcher.group(1),
                  Integer.parseInt(dateMatcher.group(2)),
                  section == SplitColumnSection.PAYMENTS);
        } else if (!pendingTransaction.hasPostDate()) {
          pendingTransaction.setPostDate();
        } else {
          pendingTransaction.addDescriptionLine(line);
        }
        continue;
      }

      if (pendingTransaction != null && pendingTransaction.hasPostDate()) {
        pendingTransaction.addDescriptionLine(line);
      }
    }

    log.debug(
        "Split-column parser extracted {} Capital One Credit Monthly Statement rows",
        transactions.size());
    return transactions;
  }

  private String normalizeLine(String line) {
    return line.trim().replaceAll("\\s+", " ");
  }

  private boolean isSplitColumnAmountLine(String line) {
    return SPLIT_COLUMN_AMOUNT_PATTERN.matcher(line).matches();
  }

  private boolean shouldStopSplitColumnParsing(
      String line, SplitColumnPendingTransaction pendingTransaction) {
    return pendingTransaction == null && SPLIT_COLUMN_STOP_PATTERN.matcher(line).find();
  }

  private boolean shouldSkipSplitColumnLine(String line) {
    return SKIP_PATTERN.matcher(line).find() || SPLIT_COLUMN_DETAIL_PATTERN.matcher(line).find();
  }

  private boolean containsTransactionTable(String text) {
    return containsTransactionSection(text)
        && TRANSACTION_TABLE_HEADER_PATTERN.matcher(text).find();
  }

  private boolean containsTransactionSection(String text) {
    String[] lines = text.split("\\r?\\n");
    for (String rawLine : lines) {
      var line = normalizeLine(rawLine);
      if (PAYMENTS_SECTION_PATTERN.matcher(line).find()
          || TRANSACTIONS_SECTION_PATTERN.matcher(line).find()
          || line.endsWith(": Transactions")) {
        return true;
      }
    }
    return false;
  }

  private PreviewTransaction toPreviewTransaction(
      String amountLine,
      SplitColumnPendingTransaction pendingTransaction,
      StatementPeriod period,
      String accountId) {
    var amountMatcher = SPLIT_COLUMN_AMOUNT_PATTERN.matcher(amountLine);
    if (!amountMatcher.matches()) {
      throw new IllegalArgumentException("Invalid split-column amount line");
    }

    var date =
        parseDate(
            pendingTransaction.transactionMonth(), pendingTransaction.transactionDay(), period);
    var amount = parseAmount(amountMatcher.group(2));
    var type =
        pendingTransaction.creditSection() || amountMatcher.group(1) != null
            ? TransactionType.CREDIT
            : TransactionType.DEBIT;

    return new PreviewTransaction(
        date,
        pendingTransaction.description(),
        amount,
        type,
        null,
        BANK_NAME,
        CURRENCY_CODE,
        accountId);
  }

  private PreviewTransaction parseTransactionLine(
      String line, StatementPeriod period, String accountId, boolean isCredit) {
    Matcher matcher = TRANSACTION_PATTERN.matcher(line);
    if (!matcher.find()) {
      log.trace("Line did not match transaction pattern: {}", line);
      return null;
    }

    String transMonthStr = matcher.group(1);
    int transDay = Integer.parseInt(matcher.group(2));
    // Post date is groups 3 and 4, but we use transaction date
    String description = matcher.group(5).trim();
    boolean hasNegativeSign = matcher.group(6) != null;
    String amountStr = matcher.group(7);

    // Parse date using statement period context
    LocalDate date = parseDate(transMonthStr, transDay, period);

    // Parse amount
    BigDecimal amount = parseAmount(amountStr);

    // Determine transaction type
    // In the payments section, amounts are credits (payments, refunds)
    // In the transactions section, amounts are debits (purchases)
    // The "-" sign in the PDF indicates credits
    TransactionType type;
    if (isCredit || hasNegativeSign) {
      type = TransactionType.CREDIT;
    } else {
      type = TransactionType.DEBIT;
    }

    return new PreviewTransaction(
        date, description, amount, type, null, BANK_NAME, CURRENCY_CODE, accountId);
  }

  private LocalDate parseDate(String monthStr, int day, StatementPeriod period) {
    Month transactionMonth = parseMonth(monthStr);

    // Determine year based on statement period
    // If transaction month is after the end month, it's from the start year
    // If transaction month is before or equal to end month, check if it makes sense
    int year;
    if (period.startYear() != period.endYear()) {
      // Statement spans two years (e.g., Dec 2024 - Jan 2025)
      if (transactionMonth.getValue() >= period.startMonth().getValue()) {
        year = period.startYear();
      } else {
        year = period.endYear();
      }
    } else {
      // Same year statement
      year = period.endYear();
    }

    return LocalDate.of(year, transactionMonth, day);
  }

  private BigDecimal parseAmount(String amountStr) {
    String cleaned = amountStr.replace(",", "");
    return new BigDecimal(cleaned);
  }

  /** Represents the statement period with start and end dates. */
  private record StatementPeriod(
      Month startMonth, int startDay, int startYear, Month endMonth, int endDay, int endYear) {}

  private enum SplitColumnSection {
    NONE,
    PAYMENTS,
    TRANSACTIONS
  }

  private static final class SplitColumnPendingTransaction {

    private final String transactionMonth;
    private final int transactionDay;
    private final boolean creditSection;
    private final List<String> descriptionLines = new ArrayList<>();
    private boolean hasPostDate;

    private SplitColumnPendingTransaction(
        String transactionMonth, int transactionDay, boolean creditSection) {
      this.transactionMonth = transactionMonth;
      this.transactionDay = transactionDay;
      this.creditSection = creditSection;
    }

    private String transactionMonth() {
      return transactionMonth;
    }

    private int transactionDay() {
      return transactionDay;
    }

    private boolean creditSection() {
      return creditSection;
    }

    private boolean hasPostDate() {
      return hasPostDate;
    }

    private void setPostDate() {
      hasPostDate = true;
    }

    private void addDescriptionLine(String descriptionLine) {
      descriptionLines.add(descriptionLine);
    }

    private boolean isReadyForAmount() {
      return hasPostDate() && !descriptionLines.isEmpty();
    }

    private String description() {
      return String.join(" ", descriptionLines);
    }
  }
}
