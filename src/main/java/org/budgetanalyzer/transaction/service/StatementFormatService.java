package org.budgetanalyzer.transaction.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.StatementFormatScope;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.service.dto.CsvColumnParserConfig;
import org.budgetanalyzer.transaction.service.dto.StatementFormatCommand;
import org.budgetanalyzer.transaction.service.dto.StatementFormatPatch;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractorRegistry;

/** Service for managing statement format configurations. */
@Service
public class StatementFormatService {

  private static final Logger log = LoggerFactory.getLogger(StatementFormatService.class);

  private final StatementFormatRepository statementFormatRepository;
  private final ParserRevisionRepository parserRevisionRepository;
  private final StatementExtractorRegistry statementExtractorRegistry;
  private final ObjectMapper objectMapper;

  /**
   * Constructs a new StatementFormatService.
   *
   * @param statementFormatRepository repository for format persistence
   * @param parserRevisionRepository repository for parser revision persistence
   * @param statementExtractorRegistry registry to notify of format changes
   * @param objectMapper JSON mapper for parser configuration
   */
  @Autowired
  public StatementFormatService(
      StatementFormatRepository statementFormatRepository,
      ParserRevisionRepository parserRevisionRepository,
      StatementExtractorRegistry statementExtractorRegistry,
      ObjectMapper objectMapper) {
    this.statementFormatRepository = statementFormatRepository;
    this.parserRevisionRepository = parserRevisionRepository;
    this.statementExtractorRegistry = statementExtractorRegistry;
    this.objectMapper = objectMapper;
  }

  /**
   * Returns statement formats visible to the current user.
   *
   * @param userId current user ID
   * @param canReadAny whether the user can read all statement formats
   * @return list of visible formats
   */
  @Transactional(readOnly = true)
  public List<StatementFormat> getVisibleFormats(String userId, boolean canReadAny) {
    if (canReadAny) {
      return statementFormatRepository.findAll();
    }
    return statementFormatRepository.findVisibleToUser(userId);
  }

  /**
   * Finds a statement format visible to the current user by ID.
   *
   * @param id statement format ID
   * @param userId current user ID
   * @param canReadAny whether the user can read all statement formats
   * @return the format
   * @throws ResourceNotFoundException if not found
   */
  @Transactional(readOnly = true)
  public StatementFormat getById(Long id, String userId, boolean canReadAny) {
    if (canReadAny) {
      return statementFormatRepository.findById(id).orElseThrow(() -> statementFormatNotFound(id));
    }

    return statementFormatRepository
        .findVisibleToUserById(id, userId)
        .orElseThrow(() -> statementFormatNotFound(id));
  }

  /**
   * Finds an enabled statement format visible to the current user by ID.
   *
   * @param id statement format ID
   * @param userId current user ID
   * @return the format
   * @throws ResourceNotFoundException if not found
   */
  @Transactional(readOnly = true)
  public StatementFormat getEnabledVisibleById(Long id, String userId) {
    return statementFormatRepository
        .findEnabledVisibleToUser(id, userId)
        .orElseThrow(() -> statementFormatNotFound(id));
  }

  /**
   * Creates a new statement format.
   *
   * @param command the creation command
   * @param userId current user ID
   * @param canWriteAny whether the user can create system formats
   * @return the created format
   */
  @Transactional
  public StatementFormat createFormat(
      StatementFormatCommand command, String userId, boolean canWriteAny) {
    var requestedScope = command.scope() == null ? StatementFormatScope.USER : command.scope();
    if (requestedScope == StatementFormatScope.SYSTEM && !canWriteAny) {
      throw new BusinessException(
          "Creating system statement formats requires statementformats:write:any.",
          BudgetAnalyzerError.FORMAT_NOT_SUPPORTED.name());
    }

    var format = mapToEntity(command, requestedScope, userId);
    var saved = statementFormatRepository.save(format);
    createInitialParserRevision(saved, command);

    log.info("Created statement format: {} ({})", saved.getId(), saved.getFormatType());

    if (saved.getFormatType() == FormatType.CSV) {
      statementExtractorRegistry.refreshCsvExtractors();
    }

    return saved;
  }

  /**
   * Updates an existing statement format.
   *
   * @param id statement format ID
   * @param patch the update patch
   * @param userId current user ID
   * @param canWriteAny whether the user can update all statement formats
   * @return the updated format
   * @throws ResourceNotFoundException if not found
   */
  @Transactional
  public StatementFormat updateFormat(
      Long id, StatementFormatPatch patch, String userId, boolean canWriteAny) {
    var format = findWritableFormat(id, userId, canWriteAny);

    applyUpdates(format, patch);
    var saved = statementFormatRepository.save(format);

    log.info("Updated statement format: {}", id);

    if (saved.getFormatType() == FormatType.CSV) {
      statementExtractorRegistry.refreshCsvExtractors();
    }

    return saved;
  }

  private StatementFormat mapToEntity(
      StatementFormatCommand command, StatementFormatScope scope, String userId) {
    var ownerId = scope == StatementFormatScope.USER ? userId : null;
    if (command.formatType() == FormatType.CSV) {
      if (scope == StatementFormatScope.SYSTEM) {
        return StatementFormat.createSystemCsvFormat(
            command.displayName(), command.bankName(), command.defaultCurrencyIsoCode());
      }
      return StatementFormat.createCsvFormat(
          command.displayName(), command.bankName(), command.defaultCurrencyIsoCode(), ownerId);
    }
    if (scope == StatementFormatScope.SYSTEM) {
      return StatementFormat.createSystemPdfFormat(
          command.displayName(), command.bankName(), command.defaultCurrencyIsoCode());
    }
    return StatementFormat.createUserPdfFormat(
        command.displayName(), command.bankName(), command.defaultCurrencyIsoCode(), ownerId);
  }

  private void createInitialParserRevision(
      StatementFormat statementFormat, StatementFormatCommand command) {
    if (statementFormat.getFormatType() != FormatType.CSV || command.dateHeader() == null) {
      return;
    }

    var parserConfig = serializeCsvConfig(command);
    var parserRevision = ParserRevision.createCsvColumnConfig(statementFormat, 1, parserConfig);
    if (parserRevisionRepository != null) {
      parserRevisionRepository.save(parserRevision);
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
    if (patch.enabled() != null) {
      format.setEnabled(patch.enabled());
    }
  }

  private StatementFormat findWritableFormat(Long id, String userId, boolean canWriteAny) {
    if (canWriteAny) {
      return statementFormatRepository.findById(id).orElseThrow(() -> statementFormatNotFound(id));
    }

    return statementFormatRepository
        .findVisibleToUserById(id, userId)
        .filter(
            statementFormat ->
                statementFormat.getScope() == StatementFormatScope.USER
                    && userId.equals(statementFormat.getOwnerId()))
        .orElseThrow(() -> statementFormatNotFound(id));
  }

  private String serializeCsvConfig(StatementFormatCommand command) {
    var csvColumnParserConfig =
        new CsvColumnParserConfig(
            command.dateHeader(),
            command.dateFormat(),
            command.descriptionHeader(),
            command.creditHeader(),
            command.debitHeader(),
            command.typeHeader(),
            command.categoryHeader());
    try {
      return objectMapper.writeValueAsString(csvColumnParserConfig);
    } catch (JsonProcessingException jsonProcessingException) {
      throw new BusinessException(
          "Failed to serialize CSV parser configuration.",
          BudgetAnalyzerError.CSV_PARSING_ERROR.name(),
          jsonProcessingException);
    }
  }

  private ResourceNotFoundException statementFormatNotFound(Long id) {
    return new ResourceNotFoundException("Statement format not found with id: " + id);
  }
}
