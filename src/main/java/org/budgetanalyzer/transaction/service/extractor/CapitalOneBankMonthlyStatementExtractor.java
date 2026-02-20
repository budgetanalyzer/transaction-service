package org.budgetanalyzer.transaction.service.extractor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
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
import org.budgetanalyzer.transaction.api.response.PreviewTransaction;
import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;

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

  private static final String FORMAT_KEY = "capital-one-bank-monthly-statement";
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
  public boolean canHandle(byte[] fileContent, String filename) {
    if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
      return false;
    }

    try {
      String text = extractTextFromPdf(fileContent, 1, 2);
      return MONTHLY_STATEMENT_PATTERN.matcher(text).find();
    } catch (Exception e) {
      log.debug("Failed to check if file is Capital One Monthly Statement: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public ExtractionResult extract(byte[] fileContent, String accountId) {
    try {
      String fullText = extractTextFromPdf(fileContent, 1, Integer.MAX_VALUE);

      StatementPeriod period = extractStatementPeriod(fullText);
      log.info(
          "Extracting Capital One Monthly Statement for {} {}", period.endMonth(), period.year());

      List<PreviewTransaction> transactions = parseTransactions(fullText, period, accountId);
      log.info("Extracted {} transactions from Capital One Monthly Statement", transactions.size());

      return new ExtractionResult(transactions, Collections.emptyList());
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
    var result = extract(fileContent, accountId);
    return result.transactions().stream()
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
        log.debug("Switched to account: {}", currentAccount);
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
      log.trace("Line did not match transaction pattern: {}", line);
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
