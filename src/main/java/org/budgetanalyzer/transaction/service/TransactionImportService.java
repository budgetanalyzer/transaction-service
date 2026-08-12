package org.budgetanalyzer.transaction.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.ParserAttempt;
import org.budgetanalyzer.transaction.service.dto.ParserAttemptStatus;
import org.budgetanalyzer.transaction.service.dto.PreviewFileImportStatus;
import org.budgetanalyzer.transaction.service.dto.PreviewFileResult;
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

  private final StatementExtractorRegistry statementExtractorRegistry;
  private final StatementFormatService statementFormatService;
  private final TransactionRepository transactionRepository;
  private final FileImportTrackingService fileImportTrackingService;
  private final PreviewImportTokenService previewImportTokenService;
  private final TransactionDuplicateMatcher transactionDuplicateMatcher =
      new TransactionDuplicateMatcher();

  /**
   * Constructs a new TransactionImportService.
   *
   * @param statementExtractorRegistry the registry for looking up statement extractors
   * @param statementFormatService the service for visible statement format lookup
   * @param transactionRepository the repository for owner-scoped duplicate lookup
   * @param fileImportTrackingService the service for file import history lookup
   * @param previewImportTokenService the service for preview import token creation
   */
  public TransactionImportService(
      StatementExtractorRegistry statementExtractorRegistry,
      StatementFormatService statementFormatService,
      TransactionRepository transactionRepository,
      FileImportTrackingService fileImportTrackingService,
      PreviewImportTokenService previewImportTokenService) {
    this.statementExtractorRegistry = statementExtractorRegistry;
    this.statementFormatService = statementFormatService;
    this.transactionRepository = transactionRepository;
    this.fileImportTrackingService = fileImportTrackingService;
    this.previewImportTokenService = previewImportTokenService;
  }

  /**
   * Previews transactions from ordered files of any supported type (PDF or CSV).
   *
   * <p>The statementFormatId parameter is required and determines which top-level format to use.
   * The registry selects an active parser revision independently for each file. The operation
   * returns only after every file has been parsed and duplicate metadata has been applied.
   *
   * @param statementFormatId selected statement format ID
   * @param accountId optional account identifier to pre-fill for all transactions
   * @param files the ordered files to preview (PDF or CSV)
   * @param userId the ID of the user whose active transactions should be checked for duplicates
   * @return grouped preview result in multipart order
   * @throws BusinessException if the format is not supported or parsing fails
   */
  @Transactional(readOnly = true)
  public PreviewResult previewFiles(
      Long statementFormatId, String accountId, List<MultipartFile> files, String userId) {
    var statementFormat = statementFormatService.getEnabledVisibleById(statementFormatId, userId);
    var extractedPreviewFiles = new ArrayList<ExtractedPreviewFile>(files.size());
    for (var fileIndex = 0; fileIndex < files.size(); fileIndex++) {
      extractedPreviewFiles.add(
          extractPreviewFile(statementFormat, accountId, files.get(fileIndex), fileIndex, userId));
    }

    var markedTransactionGroups =
        transactionDuplicateMatcher.markGroupedDuplicates(
            transactionRepository,
            extractedPreviewFiles.stream().map(ExtractedPreviewFile::transactions).toList(),
            userId);
    var previewFileResults = new ArrayList<PreviewFileResult>(extractedPreviewFiles.size());
    for (var fileIndex = 0; fileIndex < extractedPreviewFiles.size(); fileIndex++) {
      var extractedPreviewFile = extractedPreviewFiles.get(fileIndex);
      previewFileResults.add(
          new PreviewFileResult(
              extractedPreviewFile.sourceFile(),
              statementFormat.getId(),
              extractedPreviewFile.previewImportToken(),
              extractedPreviewFile.fileImport(),
              markedTransactionGroups.get(fileIndex)));
    }

    return new PreviewResult(previewFileResults);
  }

  private ExtractedPreviewFile extractPreviewFile(
      StatementFormat statementFormat,
      String accountId,
      MultipartFile file,
      int fileIndex,
      String userId) {
    var originalFilename = requireOriginalFilename(file, fileIndex);
    if (file.isEmpty()) {
      throw new BusinessException(
          "Uploaded file '" + originalFilename + "' is empty.",
          BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    var fileContent = readFileContent(file, originalFilename);
    var fileCheckResult = fileImportTrackingService.checkFile(fileContent, userId);
    var fileImportStatus = PreviewFileImportStatus.from(fileCheckResult.existingImport());
    var parserAttempt = parseFile(statementFormat, fileContent, originalFilename, accountId);
    var parserRevision = parserAttempt.parserRevision();

    log.info(
        "Previewing file with statementFormatId={} parserRevisionId={}",
        statementFormat.getId(),
        parserRevision.getId());

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
        "Successfully previewed {} transactions from file index {}",
        extractedTransactions.size(),
        fileIndex);

    return new ExtractedPreviewFile(
        originalFilename, previewImportToken, fileImportStatus, extractedTransactions);
  }

  private ParserAttempt parseFile(
      StatementFormat statementFormat,
      byte[] fileContent,
      String originalFilename,
      String accountId) {
    try {
      var parserAttempts =
          statementExtractorRegistry.attemptParse(
              statementFormat, fileContent, originalFilename, accountId);
      return selectParserAttempt(statementFormat.getId(), parserAttempts);
    } catch (BusinessException businessException) {
      throw new BusinessException(
          "Failed to preview file '" + originalFilename + "': " + businessException.getMessage(),
          businessException.getCode(),
          businessException);
    }
  }

  private byte[] readFileContent(MultipartFile file, String originalFilename) {
    try {
      return file.getBytes();
    } catch (IOException ioException) {
      throw new BusinessException(
          "Failed to read uploaded file '" + originalFilename + "'.",
          BudgetAnalyzerError.CSV_PARSING_ERROR.name(),
          ioException);
    }
  }

  private String requireOriginalFilename(MultipartFile file, int fileIndex) {
    var originalFilename = file.getOriginalFilename();
    if (originalFilename == null) {
      throw new BusinessException(
          "Uploaded file part at index " + fileIndex + " must include an original filename.",
          BudgetAnalyzerError.MISSING_ORIGINAL_FILENAME.name());
    }
    var trimmedOriginalFilename = originalFilename.trim();
    if (trimmedOriginalFilename.isBlank()) {
      throw new BusinessException(
          "Uploaded file part at index " + fileIndex + " must include an original filename.",
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

  private record ExtractedPreviewFile(
      String sourceFile,
      String previewImportToken,
      PreviewFileImportStatus fileImport,
      List<PreviewTransaction> transactions) {}
}
