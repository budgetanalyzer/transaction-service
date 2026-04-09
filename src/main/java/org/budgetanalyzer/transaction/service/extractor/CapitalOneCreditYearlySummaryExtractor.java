package org.budgetanalyzer.transaction.service.extractor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;
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

  private static final String FORMAT_KEY = "capital-one-credit-yearly-statement";
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
  public boolean canHandle(byte[] fileContent, String filename) {
    if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
      return false;
    }

    try {
      String text = extractTextFromPdf(fileContent, 1, 3);
      return YEAR_END_SUMMARY_PATTERN.matcher(text).find()
          && text.toLowerCase().contains("capital one");
    } catch (Exception e) {
      log.debug("Failed to check if file is Capital One Year-End Summary: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public ExtractionResult extract(byte[] fileContent, String accountId) {
    try {
      String fullText = extractTextFromPdf(fileContent, 1, Integer.MAX_VALUE);

      int year = extractYear(fullText);
      log.info("Extracting Capital One Year-End Summary for year {}", year);

      List<PreviewTransaction> transactions = parseTransactions(fullText, year, accountId);
      log.info("Extracted {} transactions from Capital One Year-End Summary", transactions.size());

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
        log.debug("Switched to category: {}", currentCategory);
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
      log.trace("Line did not match transaction pattern: {}", line);
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
