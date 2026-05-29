package org.budgetanalyzer.transaction.service.extractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.core.csv.CsvParser;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.ParserType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;
import org.budgetanalyzer.transaction.service.dto.CsvColumnParserConfig;
import org.budgetanalyzer.transaction.service.dto.ParserAttempt;

/**
 * Registry for statement extractors, managing static handlers and dynamic CSV parser revisions.
 *
 * <p>Static extractors are Spring components that implement StatementExtractor. Dynamic CSV
 * extractors are created from hidden ParserRevision rows.
 */
@Service
public class StatementExtractorRegistry {

  private static final Logger log = LoggerFactory.getLogger(StatementExtractorRegistry.class);

  private final List<StatementExtractor> staticExtractors;
  private final ParserRevisionRepository parserRevisionRepository;
  private final CsvParser csvParser;
  private final ObjectMapper objectMapper;

  private final Map<Long, StatementExtractor> csvExtractorCache = new ConcurrentHashMap<>();
  private final Map<String, StatementExtractor> staticExtractorsByHandlerKey =
      new ConcurrentHashMap<>();

  /**
   * Constructs a new StatementExtractorRegistry.
   *
   * @param staticExtractors list of static extractors
   * @param parserRevisionRepository repository for parser revision entities
   * @param csvParser the CSV parser to use for dynamic extractors
   * @param objectMapper JSON mapper for parser configuration
   */
  @Autowired
  public StatementExtractorRegistry(
      List<StatementExtractor> staticExtractors,
      ParserRevisionRepository parserRevisionRepository,
      CsvParser csvParser,
      ObjectMapper objectMapper) {
    this.staticExtractors = staticExtractors;
    this.parserRevisionRepository = parserRevisionRepository;
    this.csvParser = csvParser;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void initialize() {
    log.info(
        "StatementExtractorRegistry initialized with {} static extractors",
        staticExtractors.size());
    for (var statementExtractor : staticExtractors) {
      log.info(
          "  - {} ({})",
          statementExtractor.getHandlerKey(),
          statementExtractor.getClass().getSimpleName());
      staticExtractorsByHandlerKey.put(statementExtractor.getHandlerKey(), statementExtractor);
    }
    refreshCsvExtractors();
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
    if (parserRevisionRepository == null) {
      return List.of();
    }

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
        return ParserAttempt.notApplicable(
            parserRevision, "No extractor is registered for parser revision.");
      }
      if (!statementExtractor.get().canHandle(fileContent, filename)) {
        return ParserAttempt.notApplicable(
            parserRevision, "Extractor cannot handle the uploaded file.");
      }

      var transactions = statementExtractor.get().extract(fileContent, accountId);
      if (transactions.isEmpty()) {
        return ParserAttempt.notApplicable(parserRevision, "Extractor parsed no transaction rows.");
      }

      return ParserAttempt.matched(parserRevision, statementExtractor.get(), transactions);
    } catch (BusinessException businessException) {
      return ParserAttempt.failed(
          parserRevision, businessException.getMessage(), businessException);
    }
  }

  /**
   * Returns all available extractors (static + cached dynamic).
   *
   * @return list of all extractors
   */
  public List<StatementExtractor> getAllExtractors() {
    var allStatementExtractors = new ArrayList<>(staticExtractors);
    allStatementExtractors.addAll(csvExtractorCache.values());
    return allStatementExtractors;
  }

  /**
   * Refreshes the CSV extractor cache from enabled CSV parser revisions.
   *
   * <p>Call this after adding or modifying CSV parser revisions.
   */
  public void refreshCsvExtractors() {
    csvExtractorCache.clear();

    var csvParserRevisions =
        parserRevisionRepository.findByParserTypeAndEnabledTrue(ParserType.CSV_COLUMN_CONFIG);
    for (var parserRevision : csvParserRevisions) {
      var statementFormat = parserRevision.getStatementFormat();
      if (statementFormat.getFormatType() == FormatType.CSV && statementFormat.isEnabled()) {
        var statementExtractor = createCsvExtractor(statementFormat, parserRevision);
        csvExtractorCache.put(parserRevision.getId(), statementExtractor);
      }
    }

    log.info("Refreshed CSV extractors: {} revisions loaded", csvExtractorCache.size());
  }

  private Optional<StatementExtractor> createExtractor(
      StatementFormat statementFormat, ParserRevision parserRevision) {
    if (parserRevision.getParserType() == ParserType.STATIC_HANDLER) {
      return Optional.ofNullable(staticExtractorsByHandlerKey.get(parserRevision.getHandlerKey()));
    }
    if (parserRevision.getParserType() == ParserType.CSV_COLUMN_CONFIG
        && statementFormat.getFormatType() == FormatType.CSV) {
      var statementExtractor = csvExtractorCache.get(parserRevision.getId());
      if (statementExtractor != null) {
        return Optional.of(statementExtractor);
      }

      statementExtractor = createCsvExtractor(statementFormat, parserRevision);
      csvExtractorCache.put(parserRevision.getId(), statementExtractor);
      return Optional.of(statementExtractor);
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
}
