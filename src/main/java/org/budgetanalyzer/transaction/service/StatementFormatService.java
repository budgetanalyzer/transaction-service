package org.budgetanalyzer.transaction.service;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.service.api.FieldError;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.StatementFormatScope;
import org.budgetanalyzer.transaction.domain.StatementFormatUserPreference;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatUserPreferenceRepository;
import org.budgetanalyzer.transaction.service.dto.CsvColumnParserConfig;
import org.budgetanalyzer.transaction.service.dto.StatementFormatCommand;
import org.budgetanalyzer.transaction.service.dto.StatementFormatPatch;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractorRegistry;

/** Service for managing statement format configurations. */
@Service
public class StatementFormatService {

  private static final Logger log = LoggerFactory.getLogger(StatementFormatService.class);

  private final StatementFormatRepository statementFormatRepository;
  private final StatementFormatUserPreferenceRepository statementFormatUserPreferenceRepository;
  private final ParserRevisionRepository parserRevisionRepository;
  private final StatementExtractorRegistry statementExtractorRegistry;
  private final ObjectMapper objectMapper;

  /**
   * Constructs a new StatementFormatService.
   *
   * @param statementFormatRepository repository for format persistence
   * @param statementFormatUserPreferenceRepository repository for user format preferences
   * @param parserRevisionRepository repository for parser revision persistence
   * @param statementExtractorRegistry registry to notify of format changes
   * @param objectMapper JSON mapper for parser configuration
   */
  public StatementFormatService(
      StatementFormatRepository statementFormatRepository,
      StatementFormatUserPreferenceRepository statementFormatUserPreferenceRepository,
      ParserRevisionRepository parserRevisionRepository,
      StatementExtractorRegistry statementExtractorRegistry,
      ObjectMapper objectMapper) {
    this.statementFormatRepository = statementFormatRepository;
    this.statementFormatUserPreferenceRepository = statementFormatUserPreferenceRepository;
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
    validateCreateCommand(command);

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

    validatePatch(patch);
    applyUpdates(format, patch);
    var saved = statementFormatRepository.save(format);

    log.info("Updated statement format: {}", id);

    if (saved.getFormatType() == FormatType.CSV) {
      statementExtractorRegistry.refreshCsvExtractors();
    }

    return saved;
  }

  /**
   * Hides a statement format from the current user's normal selection lists.
   *
   * @param id statement format ID
   * @param userId current user ID
   * @throws ResourceNotFoundException if the format is not visible to the user
   */
  @Transactional
  public void hideFormat(Long id, String userId) {
    var statementFormat =
        statementFormatRepository
            .findVisibleToUserById(id, userId)
            .orElseThrow(() -> statementFormatNotFound(id));
    var statementFormatUserPreference =
        statementFormatUserPreferenceRepository
            .findByStatementFormatIdAndUserId(id, userId)
            .orElseGet(() -> StatementFormatUserPreference.createHidden(statementFormat, userId));
    statementFormatUserPreference.setHidden(true);
    statementFormatUserPreferenceRepository.save(statementFormatUserPreference);

    log.info("Hid statement format {} for user {}", id, userId);
  }

  /**
   * Unhides a statement format for the current user's normal selection lists.
   *
   * @param id statement format ID
   * @param userId current user ID
   * @throws ResourceNotFoundException if the format is not visible to the user
   */
  @Transactional
  public void unhideFormat(Long id, String userId) {
    statementFormatRepository
        .findVisibleToUserById(id, userId)
        .orElseThrow(() -> statementFormatNotFound(id));
    statementFormatUserPreferenceRepository
        .findByStatementFormatIdAndUserId(id, userId)
        .ifPresent(
            statementFormatUserPreference -> {
              statementFormatUserPreference.setHidden(false);
              statementFormatUserPreferenceRepository.save(statementFormatUserPreference);
            });

    log.info("Unhid statement format {} for user {}", id, userId);
  }

  private StatementFormat mapToEntity(
      StatementFormatCommand command, StatementFormatScope scope, String userId) {
    var ownerId = scope == StatementFormatScope.USER ? userId : null;
    if (command.formatType() == FormatType.CSV) {
      if (scope == StatementFormatScope.SYSTEM) {
        return StatementFormat.createSystemCsvFormat(
            command.displayName(),
            command.bankName(),
            normalizeCurrencyIsoCode(command.defaultCurrencyIsoCode()));
      }
      return StatementFormat.createCsvFormat(
          command.displayName(),
          command.bankName(),
          normalizeCurrencyIsoCode(command.defaultCurrencyIsoCode()),
          ownerId);
    }
    throw new BusinessException(
        "Only CSV statement formats can be created through this endpoint.",
        BudgetAnalyzerError.FORMAT_NOT_SUPPORTED.name());
  }

  private void createInitialParserRevision(
      StatementFormat statementFormat, StatementFormatCommand command) {
    if (statementFormat.getFormatType() != FormatType.CSV || command.dateHeader() == null) {
      return;
    }

    var parserConfig = serializeCsvConfig(command);
    var parserRevision = ParserRevision.createCsvColumnConfig(statementFormat, 1, parserConfig);
    parserRevisionRepository.save(parserRevision);
  }

  private void applyUpdates(StatementFormat format, StatementFormatPatch patch) {
    if (patch.displayName() != null) {
      format.setDisplayName(patch.displayName());
    }
    if (patch.bankName() != null) {
      format.setBankName(patch.bankName());
    }
    if (patch.defaultCurrencyIsoCode() != null) {
      format.setDefaultCurrencyIsoCode(normalizeCurrencyIsoCode(patch.defaultCurrencyIsoCode()));
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

  private void validateCreateCommand(StatementFormatCommand command) {
    var fieldErrors = new ArrayList<FieldError>();
    validateRequired("displayName", command.displayName(), fieldErrors);
    validateRequired("bankName", command.bankName(), fieldErrors);
    validateRequired("defaultCurrencyIsoCode", command.defaultCurrencyIsoCode(), fieldErrors);
    if (command.formatType() == null) {
      fieldErrors.add(FieldError.of("formatType", "Format type is required.", null));
    } else if (command.formatType() != FormatType.CSV) {
      fieldErrors.add(
          FieldError.of(
              "formatType",
              "Only CSV statement formats can be created through this endpoint.",
              command.formatType().name()));
    }
    if (!isBlank(command.defaultCurrencyIsoCode())) {
      validateCurrencyIsoCode(command.defaultCurrencyIsoCode(), fieldErrors);
    }
    if (command.formatType() == FormatType.CSV) {
      validateRequired("dateHeader", command.dateHeader(), fieldErrors);
      validateRequired("dateFormat", command.dateFormat(), fieldErrors);
      validateRequired("descriptionHeader", command.descriptionHeader(), fieldErrors);
      validateRequired("creditHeader", command.creditHeader(), fieldErrors);
      validateRequired("debitHeader", command.debitHeader(), fieldErrors);
    }
    if (!fieldErrors.isEmpty()) {
      throw new BusinessException(
          "Statement format validation failed.",
          BudgetAnalyzerError.STATEMENT_FORMAT_VALIDATION_FAILED.name(),
          fieldErrors);
    }
  }

  private void validatePatch(StatementFormatPatch patch) {
    var fieldErrors = new ArrayList<FieldError>();
    if (patch.displayName() != null && patch.displayName().isBlank()) {
      fieldErrors.add(
          FieldError.of("displayName", "Field must not be blank.", patch.displayName()));
    }
    if (patch.bankName() != null && patch.bankName().isBlank()) {
      fieldErrors.add(FieldError.of("bankName", "Field must not be blank.", patch.bankName()));
    }
    if (patch.defaultCurrencyIsoCode() != null) {
      if (patch.defaultCurrencyIsoCode().isBlank()) {
        fieldErrors.add(
            FieldError.of(
                "defaultCurrencyIsoCode",
                "Field must not be blank.",
                patch.defaultCurrencyIsoCode()));
      } else {
        validateCurrencyIsoCode(patch.defaultCurrencyIsoCode(), fieldErrors);
      }
    }
    if (!fieldErrors.isEmpty()) {
      throw new BusinessException(
          "Statement format validation failed.",
          BudgetAnalyzerError.STATEMENT_FORMAT_VALIDATION_FAILED.name(),
          fieldErrors);
    }
  }

  private void validateRequired(String field, String value, List<FieldError> fieldErrors) {
    if (isBlank(value)) {
      fieldErrors.add(FieldError.of(field, "Field is required.", value));
    }
  }

  private void validateCurrencyIsoCode(
      String defaultCurrencyIsoCode, List<FieldError> fieldErrors) {
    try {
      Currency.getInstance(normalizeCurrencyIsoCode(defaultCurrencyIsoCode));
    } catch (IllegalArgumentException illegalArgumentException) {
      fieldErrors.add(
          FieldError.of(
              "defaultCurrencyIsoCode",
              "Default currency ISO code must be a valid ISO 4217 code.",
              defaultCurrencyIsoCode));
    }
  }

  private String normalizeCurrencyIsoCode(String defaultCurrencyIsoCode) {
    return defaultCurrencyIsoCode.toUpperCase(Locale.ROOT);
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
