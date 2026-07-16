package org.budgetanalyzer.transaction.service.extractor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * Extracts transactions from Capital One Credit Card Year-End Summary PDF statements.
 *
 * <p>Handles Capital One credit card annual summaries. Parses Section 4 (Transaction Details) which
 * contains categorized transaction tables with date, merchant name, location, and amount columns.
 *
 * <p>Format key: {@code capital-one-credit-yearly-statement}
 */
@Component
public class CapitalOneCreditYearlySummaryExtractor implements StatementExtractor {

  private static final Logger log =
      LoggerFactory.getLogger(CapitalOneCreditYearlySummaryExtractor.class);

  private static final String HANDLER_KEY = "capital-one-credit-yearly-statement";
  private static final String BANK_NAME = "Capital One";
  private static final String CURRENCY_CODE = "USD";

  // Pattern to detect this is a Capital One Year-End Summary
  private static final Pattern YEAR_END_SUMMARY_PATTERN =
      Pattern.compile("Year-End Summary\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);

  // Pattern to match transaction lines: MM/DD DESCRIPTION $AMOUNT
  // Date is at start, amount at end (negative for credits)
  private static final Pattern TRANSACTION_PATTERN =
      Pattern.compile("^(\\d{2}/\\d{2})\\s+(.+?)\\s+(-?\\$[\\d,]+\\.\\d{2})\\s*$");

  // Categories found in Section 4
  private static final List<String> CATEGORIES =
      List.of(
          "Dining",
          "Gas/Automotive",
          "Merchandise",
          "Entertainment",
          "Travel/Airfare",
          "Travel/Car Rental",
          "Travel/Lodging",
          "Travel/Other Travel",
          "Monthly Bills/Phone/Cable",
          "Monthly Bills/Internet",
          "Monthly Bills/Utilities",
          "Monthly Bills/Other Bills",
          "Services/Professional Services",
          "Services/Healthcare",
          "Services/Insurance",
          "Services/Other",
          "Other");

  // Pattern to detect category section headers
  private static final Pattern CATEGORY_HEADER_PATTERN =
      Pattern.compile(
          "^\\s*(Dining|Gas/Automotive|Merchandise|Entertainment|"
              + "Travel/Airfare|Travel/Car Rental|Travel/Lodging|Travel/Other Travel|"
              + "Monthly Bills/Phone/Cable|Monthly Bills/Internet|Monthly Bills/Utilities|"
              + "Monthly Bills/Other Bills|"
              + "Services/Professional Services|Services/Healthcare|"
              + "Services/Insurance|Services/Other|"
              + "Other)\\s*$",
          Pattern.CASE_INSENSITIVE);

  // Lines to skip
  private static final Pattern SKIP_PATTERN =
      Pattern.compile(
          "^(Date|Merchant Name|Merchant Location|Amount|Deduct|"
              + "Card Ending in|TOTAL CHARGES|TOTAL CREDITS|TOTAL\\s+|"
              + "Section 4|Transaction Details|Page \\d+|Year-End Summary|"
              + "cont'd|cont´d).*",
          Pattern.CASE_INSENSITIVE);

  @Override
  public ParserAttempt attempt(
      ParserRevision parserRevision, byte[] fileContent, String filename, String accountId) {
    if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
      return ParserAttempt.notApplicable(parserRevision);
    }

    String fullText;
    try {
      fullText = extractTextFromPdf(fileContent);
    } catch (Exception e) {
      log.debug("Failed to check if file is Capital One Year-End Summary: {}", e.getMessage());
      return ParserAttempt.notApplicable(parserRevision);
    }
    if (!YEAR_END_SUMMARY_PATTERN.matcher(fullText).find()
        || !fullText.toLowerCase().contains("capital one")) {
      return ParserAttempt.notApplicable(parserRevision);
    }

    try {
      int year = extractYear(fullText);
      log.info("Extracting Capital One Year-End Summary for year {}", year);

      List<PreviewTransaction> transactions = parseTransactions(fullText, year, accountId);
      log.info("Extracted {} transactions from Capital One Year-End Summary", transactions.size());

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

  private int extractYear(String text) {
    Matcher matcher = YEAR_END_SUMMARY_PATTERN.matcher(text);
    if (matcher.find()) {
      return Integer.parseInt(matcher.group(1));
    }
    throw new BusinessException(
        "Could not determine year from Capital One Year-End Summary PDF",
        BudgetAnalyzerError.PDF_PARSING_ERROR.name());
  }

  private List<PreviewTransaction> parseTransactions(String text, int year, String accountId) {
    List<PreviewTransaction> transactions = new ArrayList<>();
    String currentCategory = null;
    boolean inSection4 = false;

    String[] lines = text.split("\\r?\\n");

    for (String line : lines) {
      line = line.trim();
      if (line.isEmpty()) {
        continue;
      }

      // Detect start of Section 4
      if (line.contains("Section 4") || line.contains("Transaction Details")) {
        inSection4 = true;
        continue;
      }

      if (!inSection4) {
        continue;
      }

      // Check for category header
      Matcher categoryMatcher = CATEGORY_HEADER_PATTERN.matcher(line);
      if (categoryMatcher.find()) {
        currentCategory = categoryMatcher.group(1);
        log.debug("Switched statement category section");
        continue;
      }

      // Skip non-transaction lines
      if (SKIP_PATTERN.matcher(line).find()) {
        continue;
      }

      // Try to parse as transaction
      PreviewTransaction transaction = parseTransactionLine(line, year, currentCategory, accountId);
      if (transaction != null) {
        transactions.add(transaction);
      }
    }

    return transactions;
  }

  private PreviewTransaction parseTransactionLine(
      String line, int year, String category, String accountId) {
    Matcher matcher = TRANSACTION_PATTERN.matcher(line);
    if (!matcher.find()) {
      log.trace("Line did not match transaction pattern");
      return null;
    }

    String dateStr = matcher.group(1); // MM/DD
    String description = matcher.group(2).trim();
    String amountStr = matcher.group(3);

    // Parse date with year
    LocalDate date = parseDate(dateStr, year);

    // Parse amount (negative = credit, positive = debit)
    BigDecimal amount = parseAmount(amountStr);
    TransactionType type =
        amount.compareTo(BigDecimal.ZERO) < 0 ? TransactionType.CREDIT : TransactionType.DEBIT;

    // Store absolute value for amount
    amount = amount.abs();

    return new PreviewTransaction(
        date, description, amount, type, category, BANK_NAME, CURRENCY_CODE, accountId);
  }

  private LocalDate parseDate(String dateStr, int year) {
    String[] parts = dateStr.split("/");
    int month = Integer.parseInt(parts[0]);
    int day = Integer.parseInt(parts[1]);
    return LocalDate.of(year, month, day);
  }

  private BigDecimal parseAmount(String amountStr) {
    // Remove $ and commas, keep negative sign
    String cleaned = amountStr.replace("$", "").replace(",", "");
    return new BigDecimal(cleaned);
  }
}
