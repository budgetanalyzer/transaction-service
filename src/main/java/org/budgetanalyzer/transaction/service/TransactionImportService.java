package org.budgetanalyzer.transaction.service;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.ParserAttempt;
import org.budgetanalyzer.transaction.service.dto.ParserAttemptStatus;
import org.budgetanalyzer.transaction.service.dto.PreviewFileImportStatus;
import org.budgetanalyzer.transaction.service.dto.PreviewResult;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractorRegistry;

/**
 * Service for importing transactions from statement files (CSV, PDF, etc.).
 *
 * <p>Uses the StatementExtractorRegistry to find the appropriate extractor based on the selected
 * statement format ID. All file types are handled through the unified StatementExtractor interface.
 */
@Service
public class TransactionImportService {

  private static final Logger log = LoggerFactory.getLogger(TransactionImportService.class);

  private final StatementExtractorRegistry extractorRegistry;
  private final StatementFormatService statementFormatService;
  private final TransactionRepository transactionRepository;
  private final FileImportTrackingService fileImportTrackingService;
  private final PreviewImportTokenService previewImportTokenService;
  private final TransactionDuplicateMatcher transactionDuplicateMatcher =
      new TransactionDuplicateMatcher();

  /**
   * Constructs a new TransactionImportService.
   *
   * @param extractorRegistry the registry for looking up statement extractors
   * @param statementFormatService the service for visible statement format lookup
   * @param transactionRepository the repository for owner-scoped duplicate lookup
   * @param fileImportTrackingService the service for file import history lookup
   * @param previewImportTokenService the service for preview import token creation
   */
  public TransactionImportService(
      StatementExtractorRegistry extractorRegistry,
      StatementFormatService statementFormatService,
      TransactionRepository transactionRepository,
      FileImportTrackingService fileImportTrackingService,
      PreviewImportTokenService previewImportTokenService) {
    this.extractorRegistry = extractorRegistry;
    this.statementFormatService = statementFormatService;
    this.transactionRepository = transactionRepository;
    this.fileImportTrackingService = fileImportTrackingService;
    this.previewImportTokenService = previewImportTokenService;
  }

  /**
   * Previews transactions from any supported file type (PDF or CSV).
   *
   * <p>The statementFormatId parameter is required and determines which top-level format to use.
   * The registry selects the active parser revision for that format.
   *
   * @param statementFormatId selected statement format ID
   * @param accountId optional account identifier to pre-fill for all transactions
   * @param file the file to preview (PDF or CSV)
   * @param userId the ID of the user whose active transactions should be checked for duplicates
   * @return PreviewResult containing extracted transactions
   * @throws BusinessException if the format is not supported or parsing fails
   */
  public PreviewResult previewFile(
      Long statementFormatId, String accountId, MultipartFile file, String userId) {
    var statementFormat = statementFormatService.getEnabledVisibleById(statementFormatId, userId);
    var originalFilename = requireOriginalFilename(file);
    if (file.isEmpty()) {
      throw new BusinessException("File is empty", BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    var fileContent = readFileContent(file);
    var fileCheckResult = fileImportTrackingService.checkFile(fileContent, userId);
    var fileImportStatus = PreviewFileImportStatus.from(fileCheckResult.existingImport());
    var parserAttempts =
        extractorRegistry.attemptParse(statementFormat, fileContent, originalFilename, accountId);
    var parserAttempt = selectParserAttempt(statementFormatId, parserAttempts);
    var parserRevision = parserAttempt.parserRevision();

    log.info(
        "Previewing file with statementFormatId={} parserRevisionId={}: {}",
        statementFormat.getId(),
        parserRevision.getId(),
        originalFilename);

    var previewImportToken =
        previewImportTokenService.createToken(
            userId,
            fileCheckResult.hash(),
            originalFilename,
            statementFormat.getId(),
            parserRevision.getId(),
            accountId,
            file.getSize());
    var extractedTransactions = parserAttempt.transactions();

    log.info(
        "Successfully previewed {} transactions from file {}",
        extractedTransactions.size(),
        originalFilename);

    var transactions = markDuplicates(extractedTransactions, userId);

    return new PreviewResult(
        originalFilename,
        statementFormat.getId(),
        previewImportToken,
        fileImportStatus,
        transactions);
  }

  /**
   * Legacy preview entry point that resolves an extractor by format key for old tests.
   *
   * @param format legacy format key
   * @param accountId optional account identifier to pre-fill for all transactions
   * @param file the file to preview
   * @param userId the ID of the user whose active transactions should be checked for duplicates
   * @return PreviewResult containing extracted transactions
   */
  public PreviewResult previewFile(
      String format, String accountId, MultipartFile file, String userId) {
    var extractor =
        extractorRegistry
            .findByFormat(format)
            .orElseThrow(
                () ->
                    new BusinessException(
                        "Format not supported: " + format,
                        BudgetAnalyzerError.FORMAT_NOT_SUPPORTED.name()));

    var originalFilename = requireOriginalFilename(file);
    if (file.isEmpty()) {
      throw new BusinessException("File is empty", BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    log.info(
        "Previewing file with legacy extractor '{}': {}",
        extractor.getFormatKey(),
        originalFilename);

    var fileContent = readFileContent(file);
    var fileCheckResult = fileImportTrackingService.checkFile(fileContent, userId);
    var fileImportStatus = PreviewFileImportStatus.from(fileCheckResult.existingImport());
    var previewImportToken =
        previewImportTokenService.createToken(
            userId, fileCheckResult.hash(), originalFilename, format, accountId, file.getSize());
    var extractedTransactions = extractor.extract(fileContent, accountId);
    var transactions = markDuplicates(extractedTransactions, userId);

    return new PreviewResult(
        originalFilename, format, previewImportToken, fileImportStatus, transactions);
  }

  private byte[] readFileContent(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new BusinessException(
          "Failed to read file: " + e.getMessage(),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name(),
          e);
    }
  }

  private String requireOriginalFilename(MultipartFile file) {
    var originalFilename = file.getOriginalFilename();
    if (originalFilename == null) {
      throw new BusinessException(
          "Uploaded file must include an original filename.",
          BudgetAnalyzerError.MISSING_ORIGINAL_FILENAME.name());
    }
    var trimmedOriginalFilename = originalFilename.trim();
    if (trimmedOriginalFilename.isBlank()) {
      throw new BusinessException(
          "Uploaded file must include an original filename.",
          BudgetAnalyzerError.MISSING_ORIGINAL_FILENAME.name());
    }
    return trimmedOriginalFilename;
  }

  private ParserAttempt selectParserAttempt(
      Long statementFormatId, List<ParserAttempt> parserAttempts) {
    var matchedParserAttempts =
        parserAttempts.stream()
            .filter(parserAttempt -> parserAttempt.status() == ParserAttemptStatus.MATCHED)
            .toList();
    if (!matchedParserAttempts.isEmpty()) {
      if (matchedParserAttempts.size() > 1) {
        log.info(
            "Multiple parser revisions matched statementFormatId={}; selected parserRevisionId={}",
            statementFormatId,
            matchedParserAttempts.getFirst().parserRevision().getId());
      }
      return matchedParserAttempts.getFirst();
    }

    if (parserAttempts.size() == 1
        && parserAttempts.getFirst().status() == ParserAttemptStatus.FAILED) {
      throw parserAttempts.getFirst().failure();
    }

    var failedCount =
        parserAttempts.stream()
            .filter(parserAttempt -> parserAttempt.status() == ParserAttemptStatus.FAILED)
            .count();
    log.info(
        "No parser revision matched statementFormatId={}; attempts={} failed={}",
        statementFormatId,
        parserAttempts.size(),
        failedCount);
    throw new BusinessException(
        "No active parser revision could parse statement format: " + statementFormatId,
        BudgetAnalyzerError.FORMAT_NOT_SUPPORTED.name());
  }

  private List<PreviewTransaction> markDuplicates(
      List<PreviewTransaction> transactions, String userId) {
    return transactionDuplicateMatcher.markDuplicates(transactionRepository, transactions, userId);
  }
}
