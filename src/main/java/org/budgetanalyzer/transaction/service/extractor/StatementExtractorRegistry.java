package org.budgetanalyzer.transaction.service.extractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.budgetanalyzer.core.csv.CsvParser;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;

/**
 * Registry for statement extractors, managing both static (PDF) and dynamic (CSV) extractors.
 *
 * <p>Static extractors are Spring components that implement StatementExtractor (e.g., PDF
 * extractors). Dynamic extractors are created from database StatementFormat entities for CSV
 * formats.
 */
@Service
public class StatementExtractorRegistry {

  private static final Logger log = LoggerFactory.getLogger(StatementExtractorRegistry.class);

  private final List<StatementExtractor> staticExtractors;
  private final StatementFormatRepository formatRepository;
  private final CsvParser csvParser;

  private final Map<String, StatementExtractor> csvExtractorCache = new ConcurrentHashMap<>();

  /**
   * Constructs a new StatementExtractorRegistry.
   *
   * @param staticExtractors list of static extractors (PDF components)
   * @param formatRepository repository for statement format entities
   * @param csvParser the CSV parser to use for dynamic extractors
   */
  public StatementExtractorRegistry(
      List<StatementExtractor> staticExtractors,
      StatementFormatRepository formatRepository,
      CsvParser csvParser) {
    this.staticExtractors = staticExtractors;
    this.formatRepository = formatRepository;
    this.csvParser = csvParser;
  }

  @PostConstruct
  void initialize() {
    log.info(
        "StatementExtractorRegistry initialized with {} static extractors",
        staticExtractors.size());
    for (var extractor : staticExtractors) {
      log.info("  - {} ({})", extractor.getFormatKey(), extractor.getClass().getSimpleName());
    }
    refreshCsvExtractors();
  }

  /**
   * Finds an extractor by format key.
   *
   * <p>First checks static extractors (PDF components), then looks up CSV formats from the
   * database.
   *
   * @param formatKey the format identifier
   * @return the extractor if found, empty otherwise
   */
  public Optional<StatementExtractor> findByFormat(String formatKey) {
    // Check static extractors first (PDF @Components)
    for (var extractor : staticExtractors) {
      if (extractor.getFormatKey().equals(formatKey)) {
        return Optional.of(extractor);
      }
    }

    // Check CSV extractor cache
    var csvExtractor = csvExtractorCache.get(formatKey);
    if (csvExtractor != null) {
      return Optional.of(csvExtractor);
    }

    // Try to load from database (may be newly added format)
    return formatRepository
        .findByFormatKeyAndEnabledTrue(formatKey)
        .filter(f -> f.getFormatType() == FormatType.CSV)
        .map(this::createCsvExtractor)
        .map(
            extractor -> {
              csvExtractorCache.put(formatKey, extractor);
              return extractor;
            });
  }

  /**
   * Returns all available extractors (static + dynamic).
   *
   * @return list of all extractors
   */
  public List<StatementExtractor> getAllExtractors() {
    var all = new ArrayList<>(staticExtractors);
    all.addAll(csvExtractorCache.values());
    return all;
  }

  /**
   * Refreshes the CSV extractor cache from the database.
   *
   * <p>Call this after adding or modifying StatementFormat entities.
   */
  public void refreshCsvExtractors() {
    csvExtractorCache.clear();

    var csvFormats = formatRepository.findByFormatTypeAndEnabledTrue(FormatType.CSV);
    for (var format : csvFormats) {
      var extractor = createCsvExtractor(format);
      csvExtractorCache.put(format.getFormatKey(), extractor);
    }

    log.info("Refreshed CSV extractors: {} formats loaded", csvFormats.size());
    for (var format : csvFormats) {
      log.info("  - {} ({})", format.getFormatKey(), format.getBankName());
    }
  }

  private ConfigurableCsvStatementExtractor createCsvExtractor(StatementFormat format) {
    return new ConfigurableCsvStatementExtractor(format, csvParser);
  }
}
