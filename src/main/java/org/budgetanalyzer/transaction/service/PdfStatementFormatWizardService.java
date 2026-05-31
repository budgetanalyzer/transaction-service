package org.budgetanalyzer.transaction.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAnalysisResult;
import org.budgetanalyzer.transaction.service.dto.PdfWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.PdfWizardTableCandidate;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextExtractionService;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextTableCandidate;

/** Service for stateless text-PDF statement format wizard analysis. */
@Service
public class PdfStatementFormatWizardService {

  private static final int MIN_TRANSACTION_ROWS = 2;
  private static final double MIN_CONFIDENT_CANDIDATE_SCORE = 0.55;
  private static final Pattern NUMERIC_DATE_PATTERN =
      Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b");
  private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b");
  private static final Pattern MONTH_DATE_PATTERN =
      Pattern.compile(
          "\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\s+"
              + "\\d{1,2}(?:,?\\s+\\d{2,4})?\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern AMOUNT_PATTERN =
      Pattern.compile("\\(?-?\\$?\\s*\\d{1,3}(?:,\\d{3})*(?:\\.\\d{2})?\\)?");

  private final PdfTextExtractionService pdfTextExtractionService;

  /**
   * Constructs a PDF statement format wizard service.
   *
   * @param pdfTextExtractionService text-PDF extraction service
   */
  public PdfStatementFormatWizardService(PdfTextExtractionService pdfTextExtractionService) {
    this.pdfTextExtractionService = pdfTextExtractionService;
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
    var confidence = calculateConfidence(pdfTextTableCandidate, columnConfidences, amountMode);
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
      return normalizedValue.matches(".*\\b\\d{4}\\b.*") ? "MMM d uuuu" : "MMM d";
    }
    if (NUMERIC_DATE_PATTERN.matcher(normalizedValue).find()) {
      var dateParts = normalizedValue.split("/");
      if (dateParts.length >= 3 && Integer.parseInt(dateParts[0]) > 12) {
        return dateParts[2].length() == 4 ? "dd/MM/uuuu" : "dd/MM/uu";
      }
      return dateParts[2].length() == 4 ? "MM/dd/uuuu" : "MM/dd/uu";
    }
    return null;
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
      PdfWizardAmountMode amountMode) {
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
    return roundToTwoDecimals(
        (columnScore * 0.7)
            + (rowContinuityScore(pdfTextTableCandidate) * 0.2)
            + (minimumRowsScore(pdfTextTableCandidate) * 0.1));
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
        || ISO_DATE_PATTERN.matcher(value).find()
        || MONTH_DATE_PATTERN.matcher(value).find();
  }

  private boolean isAmountLike(String value) {
    if (value == null) {
      return false;
    }
    return AMOUNT_PATTERN.matcher(value.replace(",", "")).find();
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
