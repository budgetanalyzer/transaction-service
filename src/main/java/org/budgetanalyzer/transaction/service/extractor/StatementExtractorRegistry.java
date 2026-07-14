package org.budgetanalyzer.transaction.service.extractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.core.csv.CsvParser;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.ParserType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;
import org.budgetanalyzer.transaction.service.PdfTextTableParserConfigValidator;
import org.budgetanalyzer.transaction.service.dto.CsvColumnParserConfig;
import org.budgetanalyzer.transaction.service.dto.ParserAttempt;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableParserConfig;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextExtractionService;

/**
 * Registry for statement extractors, managing static handlers and dynamic parser revisions.
 *
 * <p>Static extractors are Spring components keyed by handler ID. Dynamic extractors are created
 * directly from the parser revision being attempted, so revision changes do not require a registry
 * cache refresh.
 */
@Service
public class StatementExtractorRegistry {

  private static final Logger log = LoggerFactory.getLogger(StatementExtractorRegistry.class);

  private final ParserRevisionRepository parserRevisionRepository;
  private final CsvParser csvParser;
  private final ObjectMapper objectMapper;
  private final PdfTextExtractionService pdfTextExtractionService;
  private final PdfTextTableParserConfigValidator pdfTextTableParserConfigValidator =
      new PdfTextTableParserConfigValidator();

  private final Map<String, StatementExtractor> staticExtractorsByHandlerKey;

  /**
   * Constructs a new StatementExtractorRegistry.
   *
   * @param staticExtractors list of static extractors
   * @param parserRevisionRepository repository for parser revision entities
   * @param csvParser the CSV parser to use for dynamic extractors
   * @param objectMapper JSON mapper for parser configuration
   * @param pdfTextExtractionService text-PDF extraction service
   */
  public StatementExtractorRegistry(
      List<StatementExtractor> staticExtractors,
      ParserRevisionRepository parserRevisionRepository,
      CsvParser csvParser,
      ObjectMapper objectMapper,
      PdfTextExtractionService pdfTextExtractionService) {
    this.parserRevisionRepository = parserRevisionRepository;
    this.csvParser = csvParser;
    this.objectMapper = objectMapper;
    this.pdfTextExtractionService = pdfTextExtractionService;
    this.staticExtractorsByHandlerKey = buildStaticExtractorMap(staticExtractors);
    log.info(
        "StatementExtractorRegistry initialized with {} static extractors",
        staticExtractorsByHandlerKey.size());
  }

  private Map<String, StatementExtractor> buildStaticExtractorMap(
      List<StatementExtractor> staticExtractors) {
    var extractorMap = new HashMap<String, StatementExtractor>();
    for (var statementExtractor : staticExtractors) {
      log.info(
          "  - {} ({})",
          statementExtractor.getHandlerKey(),
          statementExtractor.getClass().getSimpleName());
      var previousExtractor =
          extractorMap.put(statementExtractor.getHandlerKey(), statementExtractor);
      if (previousExtractor != null) {
        throw new IllegalStateException(
            "Duplicate statement extractor handler key: " + statementExtractor.getHandlerKey());
      }
    }
    return Map.copyOf(extractorMap);
  }

  /**
   * Attempts every active parser revision under a statement format in deterministic selection
   * order.
   *
   * @param statementFormat selected top-level statement format
   * @param fileContent uploaded file bytes
   * @param filename original uploaded filename
   * @param accountId optional account ID to pre-fill for all transactions
   * @return parser attempts in priority and revision order
   */
  public List<ParserAttempt> attemptParse(
      StatementFormat statementFormat, byte[] fileContent, String filename, String accountId) {
    var parserRevisions =
        parserRevisionRepository
            .findByStatementFormatIdAndEnabledTrueOrderByPriorityDescRevisionNumberDesc(
                statementFormat.getId());
    var parserAttempts = new ArrayList<ParserAttempt>();
    for (var parserRevision : parserRevisions) {
      parserAttempts.add(
          attemptParse(statementFormat, parserRevision, fileContent, filename, accountId));
    }
    return parserAttempts;
  }

  private ParserAttempt attemptParse(
      StatementFormat statementFormat,
      ParserRevision parserRevision,
      byte[] fileContent,
      String filename,
      String accountId) {
    try {
      var statementExtractor = createExtractor(statementFormat, parserRevision);
      if (statementExtractor.isEmpty()) {
        return ParserAttempt.notApplicable(parserRevision);
      }
      return statementExtractor.get().attempt(parserRevision, fileContent, filename, accountId);
    } catch (BusinessException businessException) {
      return ParserAttempt.failed(parserRevision, businessException);
    }
  }

  private Optional<StatementExtractor> createExtractor(
      StatementFormat statementFormat, ParserRevision parserRevision) {
    if (parserRevision.getParserType() == ParserType.STATIC_HANDLER) {
      return Optional.ofNullable(staticExtractorsByHandlerKey.get(parserRevision.getHandlerKey()));
    }
    if (parserRevision.getParserType() == ParserType.CSV_COLUMN_CONFIG
        && statementFormat.getFormatType() == FormatType.CSV) {
      return Optional.of(createCsvExtractor(statementFormat, parserRevision));
    }
    if (parserRevision.getParserType() == ParserType.PDF_TEXT_TABLE_CONFIG
        && statementFormat.getFormatType() == FormatType.PDF) {
      return Optional.of(createPdfTextTableExtractor(statementFormat, parserRevision));
    }
    return Optional.empty();
  }

  private ConfigurableCsvStatementExtractor createCsvExtractor(
      StatementFormat statementFormat, ParserRevision parserRevision) {
    try {
      var csvColumnParserConfig =
          objectMapper.readValue(parserRevision.getParserConfig(), CsvColumnParserConfig.class);
      return new ConfigurableCsvStatementExtractor(
          statementFormat, parserRevision, csvColumnParserConfig, csvParser);
    } catch (Exception exception) {
      throw new BusinessException(
          "Invalid CSV parser configuration for parser revision " + parserRevision.getId(),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name(),
          exception);
    }
  }

  private ConfigurablePdfTextTableStatementExtractor createPdfTextTableExtractor(
      StatementFormat statementFormat, ParserRevision parserRevision) {
    PdfTextTableParserConfig pdfTextTableParserConfig;
    try {
      pdfTextTableParserConfig =
          objectMapper.readValue(parserRevision.getParserConfig(), PdfTextTableParserConfig.class);
    } catch (JsonProcessingException jsonProcessingException) {
      throw new BusinessException(
          "Invalid PDF text-table parser configuration for parser revision "
              + parserRevision.getId(),
          BudgetAnalyzerError.STATEMENT_FORMAT_VALIDATION_FAILED.name(),
          jsonProcessingException);
    }
    pdfTextTableParserConfigValidator.validateOrThrow(pdfTextTableParserConfig);
    return new ConfigurablePdfTextTableStatementExtractor(
        statementFormat, parserRevision, pdfTextTableParserConfig, pdfTextExtractionService);
  }
}
