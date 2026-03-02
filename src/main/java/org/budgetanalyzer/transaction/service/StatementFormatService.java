package org.budgetanalyzer.transaction.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.api.request.CreateStatementFormatRequest;
import org.budgetanalyzer.transaction.api.request.UpdateStatementFormatRequest;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
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
   * @param request the creation request
   * @return the created format
   * @throws BusinessException if format key already exists
   */
  @Transactional
  public StatementFormat createFormat(CreateStatementFormatRequest request) {
    if (statementFormatRepository.existsByFormatKey(request.formatKey())) {
      throw new BusinessException(
          "Format key already exists: " + request.formatKey(),
          BudgetAnalyzerError.FORMAT_KEY_ALREADY_EXISTS.name());
    }

    var format = mapToEntity(request);
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
   * @param request the update request
   * @return the updated format
   * @throws ResourceNotFoundException if not found
   */
  @Transactional
  public StatementFormat updateFormat(String formatKey, UpdateStatementFormatRequest request) {
    var format = getByFormatKey(formatKey);

    applyUpdates(format, request);
    var saved = statementFormatRepository.save(format);

    log.info("Updated statement format: {}", formatKey);

    // Refresh CSV extractors if a CSV format was modified
    if (saved.getFormatType() == FormatType.CSV) {
      statementExtractorRegistry.refreshCsvExtractors();
    }

    return saved;
  }

  /**
   * Disables a statement format (soft delete).
   *
   * @param formatKey the format key of the format to disable
   * @throws ResourceNotFoundException if not found
   */
  @Transactional
  public void disableFormat(String formatKey) {
    var format = getByFormatKey(formatKey);
    format.setEnabled(false);
    statementFormatRepository.save(format);

    log.info("Disabled statement format: {}", formatKey);

    // Refresh CSV extractors to remove the disabled format
    if (format.getFormatType() == FormatType.CSV) {
      statementExtractorRegistry.refreshCsvExtractors();
    }
  }

  private StatementFormat mapToEntity(CreateStatementFormatRequest request) {
    if (request.formatType() == FormatType.CSV) {
      return StatementFormat.createCsvFormat(
          request.formatKey(),
          request.displayName(),
          request.bankName(),
          request.defaultCurrencyIsoCode(),
          request.dateHeader(),
          request.dateFormat(),
          request.descriptionHeader(),
          request.creditHeader(),
          request.debitHeader(),
          request.typeHeader(),
          request.categoryHeader());
    } else {
      return StatementFormat.createPdfFormat(
          request.formatKey(),
          request.displayName(),
          request.bankName(),
          request.defaultCurrencyIsoCode());
    }
  }

  private void applyUpdates(StatementFormat format, UpdateStatementFormatRequest request) {
    if (request.displayName() != null) {
      format.setDisplayName(request.displayName());
    }
    if (request.bankName() != null) {
      format.setBankName(request.bankName());
    }
    if (request.defaultCurrencyIsoCode() != null) {
      format.setDefaultCurrencyIsoCode(request.defaultCurrencyIsoCode());
    }
    if (request.dateHeader() != null) {
      format.setDateHeader(request.dateHeader());
    }
    if (request.dateFormat() != null) {
      format.setDateFormat(request.dateFormat());
    }
    if (request.descriptionHeader() != null) {
      format.setDescriptionHeader(request.descriptionHeader());
    }
    if (request.creditHeader() != null) {
      format.setCreditHeader(request.creditHeader());
    }
    if (request.debitHeader() != null) {
      format.setDebitHeader(request.debitHeader());
    }
    if (request.typeHeader() != null) {
      format.setTypeHeader(request.typeHeader());
    }
    if (request.categoryHeader() != null) {
      format.setCategoryHeader(request.categoryHeader());
    }
    if (request.enabled() != null) {
      format.setEnabled(request.enabled());
    }
  }
}
