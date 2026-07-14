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
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;
import org.budgetanalyzer.transaction.service.dto.ParserAttempt;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

/**
 * Extracts transactions from Capital One 360 Bank Monthly Statements (PDF).
 *
 * <p>Handles Capital One 360 checking and savings account statements. Parses transaction tables
 * with columns: DATE, DESCRIPTION, CATEGORY, AMOUNT, BALANCE.
 *
 * <p>Format key: {@code capital-one-bank-monthly-statement}
 */
@Component
public class CapitalOneBankMonthlyStatementExtractor implements StatementExtractor {

  private static final Logger log =
      LoggerFactory.getLogger(CapitalOneBankMonthlyStatementExtractor.class);

  private static final String HANDLER_KEY = "capital-one-bank-monthly-statement";
  private static final String BANK_NAME = "Capital One";
  private static final String CURRENCY_CODE = "USD";

  // Pattern to detect Capital One 360 monthly statement
  private static final Pattern MONTHLY_STATEMENT_PATTERN =
      Pattern.compile("Capital One 360.*bank statement", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  // Pattern to extract statement period: "Nov 1 - Nov 30, 2025"
  private static final Pattern STATEMENT_PERIOD_PATTERN =
      Pattern.compile("(\\w{3})\\s+\\d+\\s*-\\s*(\\w{3})\\s+\\d+,\\s*(\\d{4})");

  // Pattern to detect account section headers: "360 Checking - 36012345678"
  private static final Pattern ACCOUNT_HEADER_PATTERN =
      Pattern.compile("^(360 Checking|360 Savings|Savings Now)\\s*-\\s*(\\d+)");

  // Pattern to match transaction lines: "Nov 13 Description Category +/- $Amount $Balance"
  // Date at start, amount near end (with +/- prefix), balance at very end
  private static final Pattern TRANSACTION_PATTERN =
      Pattern.compile(
          "^(\\w{3})\\s+(\\d{1,2})\\s+(.+?)\\s+(Debit|Credit)\\s+"
              + "([+-])\\s*\\$([\\d,]+\\.\\d{2})\\s+\\$[\\d,]+\\.\\d{2}\\s*$");

  // Lines to skip
  private static final Pattern SKIP_PATTERN =
      Pattern.compile(
          "^(DATE|DESCRIPTION|CATEGORY|AMOUNT|BALANCE|"
              + "Opening Balance|Closing Balance|"
              + "Page \\d+|Fees Summary|"
              + "TOTAL FOR THIS|TOTAL YEAR-TO|"
              + "Total Overdraft|Total Return Item|Total Fees|"
              + "ANNUAL PERCENTAGE|YTD INTEREST|DAYS IN STATEMENT|"
              + "\\d+\\.\\d+%|\\$\\d).*",
          Pattern.CASE_INSENSITIVE);

  @Override
  public ParserAttempt attempt(
      ParserRevision parserRevision, byte[] fileContent, String filename, String accountId) {
    if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
      return ParserAttempt.notApplicable(parserRevision);
    }

    String fullText;
    try {
      fullText = extractTextFromPdf(fileContent);
    } catch (Exception e) {
      log.debug("Failed to check if file is Capital One Monthly Statement: {}", e.getMessage());
      return ParserAttempt.notApplicable(parserRevision);
    }
    if (!MONTHLY_STATEMENT_PATTERN.matcher(fullText).find()) {
      return ParserAttempt.notApplicable(parserRevision);
    }

    try {
      StatementPeriod period = extractStatementPeriod(fullText);
      log.info(
          "Extracting Capital One Monthly Statement for {} {}", period.endMonth(), period.year());

      List<PreviewTransaction> transactions = parseTransactions(fullText, period, accountId);
      log.info("Extracted {} transactions from Capital One Monthly Statement", transactions.size());

      if (transactions.isEmpty()) {
        return ParserAttempt.notApplicable(parserRevision);
      }
      return ParserAttempt.matched(parserRevision, transactions);
    } catch (BusinessException e) {
      return ParserAttempt.failed(parserRevision, e);
    } catch (Exception e) {
      return ParserAttempt.failed(parserRevision, pdfParsingError(e));
    }
  }

  @Override
  public String getHandlerKey() {
    return HANDLER_KEY;
  }

  private String extractTextFromPdf(byte[] fileContent) throws IOException {
    try (PDDocument document = Loader.loadPDF(fileContent)) {
      PDFTextStripper stripper = new PDFTextStripper();
      return stripper.getText(document);
    }
  }

  private BusinessException pdfParsingError(Exception exception) {
    return new BusinessException(
        "Failed to extract transactions from PDF: " + exception.getMessage(),
        BudgetAnalyzerError.PDF_PARSING_ERROR.name(),
        exception);
  }

  private StatementPeriod extractStatementPeriod(String text) {
    Matcher matcher = STATEMENT_PERIOD_PATTERN.matcher(text);
    if (matcher.find()) {
      String endMonthStr = matcher.group(2);
      int year = Integer.parseInt(matcher.group(3));
      Month endMonth = parseMonth(endMonthStr);
      return new StatementPeriod(endMonth, year);
    }
    throw new BusinessException(
        "Could not determine statement period from Capital One Monthly Statement PDF",
        BudgetAnalyzerError.PDF_PARSING_ERROR.name());
  }

  private Month parseMonth(String monthStr) {
    return Month.from(
        DateTimeFormatter.ofPattern("MMM", Locale.US).parse(monthStr.substring(0, 3)));
  }

  private List<PreviewTransaction> parseTransactions(
      String text, StatementPeriod period, String accountId) {
    List<PreviewTransaction> transactions = new ArrayList<>();
    String currentAccount = null;

    String[] lines = text.split("\\r?\\n");

    for (String line : lines) {
      line = line.trim();
      if (line.isEmpty()) {
        continue;
      }

      // Check for account section header
      Matcher accountMatcher = ACCOUNT_HEADER_PATTERN.matcher(line);
      if (accountMatcher.find()) {
        currentAccount =
            accountMatcher.group(1)
                + " ..."
                + accountMatcher
                    .group(2)
                    .substring(Math.max(0, accountMatcher.group(2).length() - 4));
        log.debug("Switched statement account section");
        continue;
      }

      // Skip non-transaction lines
      if (SKIP_PATTERN.matcher(line).find()) {
        continue;
      }

      // Try to parse as transaction
      PreviewTransaction transaction =
          parseTransactionLine(line, period, currentAccount, accountId);
      if (transaction != null) {
        transactions.add(transaction);
      }
    }

    return transactions;
  }

  private PreviewTransaction parseTransactionLine(
      String line, StatementPeriod period, String currentAccount, String accountId) {
    Matcher matcher = TRANSACTION_PATTERN.matcher(line);
    if (!matcher.find()) {
      log.trace("Line did not match transaction pattern");
      return null;
    }

    String monthStr = matcher.group(1);
    int day = Integer.parseInt(matcher.group(2));
    String description = matcher.group(3).trim();
    String categoryStr = matcher.group(4);
    String sign = matcher.group(5);
    String amountStr = matcher.group(6);

    // Parse date using statement period context
    LocalDate date = parseDate(monthStr, day, period);

    // Parse amount
    BigDecimal amount = parseAmount(amountStr);

    // Determine transaction type from category column
    TransactionType type =
        "Credit".equalsIgnoreCase(categoryStr) ? TransactionType.CREDIT : TransactionType.DEBIT;

    // Use the account as category if available, otherwise use the type
    String category = currentAccount != null ? currentAccount : categoryStr;

    // Use provided accountId, or fall back to detected account
    String finalAccountId = accountId != null ? accountId : currentAccount;

    return new PreviewTransaction(
        date, description, amount, type, category, BANK_NAME, CURRENCY_CODE, finalAccountId);
  }

  private LocalDate parseDate(String monthStr, int day, StatementPeriod period) {
    Month transactionMonth = parseMonth(monthStr);

    // Handle year boundary (e.g., statement for Jan might have Dec transactions)
    int year = period.year();
    if (transactionMonth.getValue() > period.endMonth().getValue() + 1) {
      // Transaction is from previous year (e.g., Dec in a Jan statement)
      year = period.year() - 1;
    }

    return LocalDate.of(year, transactionMonth, day);
  }

  private BigDecimal parseAmount(String amountStr) {
    String cleaned = amountStr.replace(",", "");
    return new BigDecimal(cleaned);
  }

  /** Represents the statement period with end month and year. */
  private record StatementPeriod(Month endMonth, int year) {}
}
