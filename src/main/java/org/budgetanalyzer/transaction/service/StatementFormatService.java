package org.budgetanalyzer.transaction.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.service.dto.StatementFormatCommand;
import org.budgetanalyzer.transaction.service.dto.StatementFormatPatch;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractorRegistry;

/** Service for managing statement format configurations. */
@Service
public class StatementFormatService {

  private static final Logger log = LoggerFactory.getLogger(StatementFormatService.class);

  private final StatementFormatRepository statementFormatRepository;
  private final StatementExtractorRegistry statementExtractorRegistry;

  /**
   * Constructs a new StatementFormatService.
   *
   * @param statementFormatRepository repository for format persistence
   * @param statementExtractorRegistry registry to notify of format changes
   */
  public StatementFormatService(
      StatementFormatRepository statementFormatRepository,
      StatementExtractorRegistry statementExtractorRegistry) {
    this.statementFormatRepository = statementFormatRepository;
    this.statementExtractorRegistry = statementExtractorRegistry;
  }

  /**
   * Returns all statement formats.
   *
   * @return list of all formats
   */
  @Transactional(readOnly = true)
  public List<StatementFormat> getAllFormats() {
    return statementFormatRepository.findAll();
  }

  /**
   * Finds a statement format by its format key.
   *
   * @param formatKey the format key
   * @return the format
   * @throws ResourceNotFoundException if not found
   */
  @Transactional(readOnly = true)
  public StatementFormat getByFormatKey(String formatKey) {
    return statementFormatRepository
        .findByFormatKey(formatKey)
        .orElseThrow(
            () ->
                new ResourceNotFoundException("Statement format not found with key: " + formatKey));
  }

  /**
   * Creates a new statement format.
   *
   * @param command the creation command
   * @return the created format
   * @throws BusinessException if format key already exists
   */
  @Transactional
  public StatementFormat createFormat(StatementFormatCommand command) {
    if (statementFormatRepository.existsByFormatKey(command.formatKey())) {
      throw new BusinessException(
          "Format key already exists: " + command.formatKey(),
          BudgetAnalyzerError.FORMAT_KEY_ALREADY_EXISTS.name());
    }

    var format = mapToEntity(command);
    var saved = statementFormatRepository.save(format);

    log.info("Created statement format: {} ({})", saved.getFormatKey(), saved.getFormatType());

    // Refresh CSV extractors if a new CSV format was added
    if (saved.getFormatType() == FormatType.CSV) {
      statementExtractorRegistry.refreshCsvExtractors();
    }

    return saved;
  }

  /**
   * Updates an existing statement format.
   *
   * @param formatKey the format key of the format to update
   * @param patch the update patch
   * @return the updated format
   * @throws ResourceNotFoundException if not found
   */
  @Transactional
  public StatementFormat updateFormat(String formatKey, StatementFormatPatch patch) {
    var format = getByFormatKey(formatKey);

    applyUpdates(format, patch);
    var saved = statementFormatRepository.save(format);

    log.info("Updated statement format: {}", formatKey);

    // Refresh CSV extractors if a CSV format was modified
    if (saved.getFormatType() == FormatType.CSV) {
      statementExtractorRegistry.refreshCsvExtractors();
    }

    return saved;
  }

  private StatementFormat mapToEntity(StatementFormatCommand command) {
    if (command.formatType() == FormatType.CSV) {
      return StatementFormat.createCsvFormat(
          command.formatKey(),
          command.displayName(),
          command.bankName(),
          command.defaultCurrencyIsoCode(),
          command.dateHeader(),
          command.dateFormat(),
          command.descriptionHeader(),
          command.creditHeader(),
          command.debitHeader(),
          command.typeHeader(),
          command.categoryHeader());
    } else {
      return StatementFormat.createPdfFormat(
          command.formatKey(),
          command.displayName(),
          command.bankName(),
          command.defaultCurrencyIsoCode());
    }
  }

  private void applyUpdates(StatementFormat format, StatementFormatPatch patch) {
    if (patch.displayName() != null) {
      format.setDisplayName(patch.displayName());
    }
    if (patch.bankName() != null) {
      format.setBankName(patch.bankName());
    }
    if (patch.defaultCurrencyIsoCode() != null) {
      format.setDefaultCurrencyIsoCode(patch.defaultCurrencyIsoCode());
    }
    if (patch.dateHeader() != null) {
      format.setDateHeader(patch.dateHeader());
    }
    if (patch.dateFormat() != null) {
      format.setDateFormat(patch.dateFormat());
    }
    if (patch.descriptionHeader() != null) {
      format.setDescriptionHeader(patch.descriptionHeader());
    }
    if (patch.creditHeader() != null) {
      format.setCreditHeader(patch.creditHeader());
    }
    if (patch.debitHeader() != null) {
      format.setDebitHeader(patch.debitHeader());
    }
    if (patch.typeHeader() != null) {
      format.setTypeHeader(patch.typeHeader());
    }
    if (patch.categoryHeader() != null) {
      format.setCategoryHeader(patch.categoryHeader());
    }
    if (patch.enabled() != null) {
      format.setEnabled(patch.enabled());
    }
  }
}
