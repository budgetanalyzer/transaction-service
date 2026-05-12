package org.budgetanalyzer.transaction.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.PreviewDuplicateReason;
import org.budgetanalyzer.transaction.service.dto.PreviewFileImportStatus;
import org.budgetanalyzer.transaction.service.dto.PreviewResult;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractor;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractorRegistry;

/**
 * Service for importing transactions from statement files (CSV, PDF, etc.).
 *
 * <p>Uses the StatementExtractorRegistry to find the appropriate extractor based on the format key.
 * All file types are handled through the unified StatementExtractor interface.
 */
@Service
public class TransactionImportService {

  private static final Logger log = LoggerFactory.getLogger(TransactionImportService.class);

  private final StatementExtractorRegistry extractorRegistry;
  private final TransactionRepository transactionRepository;
  private final FileImportTrackingService fileImportTrackingService;
  private final PreviewImportTokenService previewImportTokenService;

  /**
   * Constructs a new TransactionImportService.
   *
   * @param extractorRegistry the registry for looking up statement extractors
   * @param transactionRepository the repository for owner-scoped duplicate lookup
   * @param fileImportTrackingService the service for file import history lookup
   * @param previewImportTokenService the service for preview import token creation
   */
  public TransactionImportService(
      StatementExtractorRegistry extractorRegistry,
      TransactionRepository transactionRepository,
      FileImportTrackingService fileImportTrackingService,
      PreviewImportTokenService previewImportTokenService) {
    this.extractorRegistry = extractorRegistry;
    this.transactionRepository = transactionRepository;
    this.fileImportTrackingService = fileImportTrackingService;
    this.previewImportTokenService = previewImportTokenService;
  }

  /**
   * Previews transactions from any supported file type (PDF or CSV).
   *
   * <p>The format parameter is required and determines which extractor to use. The extractor is
   * looked up from the StatementExtractorRegistry, which manages both static (PDF) and dynamic
   * (CSV) extractors.
   *
   * @param format the format key (e.g., "capital-one-yearly" for PDF, "capital-one" for CSV)
   * @param accountId optional account identifier to pre-fill for all transactions
   * @param file the file to preview (PDF or CSV)
   * @param userId the ID of the user whose active transactions should be checked for duplicates
   * @return PreviewResult containing extracted transactions
   * @throws BusinessException if the format is not supported or parsing fails
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

    return previewWithExtractor(extractor, accountId, file, userId);
  }

  /**
   * Previews transactions using a specific statement extractor.
   *
   * @param extractor the extractor to use
   * @param accountId optional account identifier to pre-fill for all transactions
   * @param file the file to preview
   * @param userId the ID of the user whose active transactions should be checked for duplicates
   * @return PreviewResult containing extracted transactions
   * @throws BusinessException if parsing fails
   */
  private PreviewResult previewWithExtractor(
      StatementExtractor extractor, String accountId, MultipartFile file, String userId) {
    var originalFilename = requireOriginalFilename(file);
    if (file.isEmpty()) {
      throw new BusinessException("File is empty", BudgetAnalyzerError.CSV_PARSING_ERROR.name());
    }

    log.info("Previewing file with extractor '{}': {}", extractor.getFormatKey(), originalFilename);

    var fileContent = readFileContent(file);
    var fileCheckResult = fileImportTrackingService.checkFile(fileContent, userId);
    var fileImportStatus = PreviewFileImportStatus.from(fileCheckResult.existingImport());
    var previewImportToken =
        previewImportTokenService.createToken(
            userId,
            fileCheckResult.hash(),
            originalFilename,
            extractor.getFormatKey(),
            accountId,
            file.getSize());
    var extractedTransactions = extractor.extract(fileContent, accountId);

    log.info(
        "Successfully previewed {} transactions from file {}",
        extractedTransactions.size(),
        originalFilename);

    var transactions = markDuplicates(extractedTransactions, userId);

    return new PreviewResult(
        originalFilename,
        extractor.getFormatKey(),
        previewImportToken,
        fileImportStatus,
        transactions);
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

  private List<PreviewTransaction> markDuplicates(
      List<PreviewTransaction> transactions, String userId) {
    if (transactions.isEmpty()) {
      return transactions;
    }

    var transactionKeys =
        transactions.stream()
            .map(
                previewTransaction ->
                    TransactionDuplicateKey.from(previewTransaction).toLookupValue())
            .collect(Collectors.toSet());
    var existingKeys = transactionRepository.findExistingDuplicateKeys(transactionKeys, userId);
    var seenKeys = new HashSet<String>();
    var markedTransactions = new ArrayList<PreviewTransaction>(transactions.size());

    for (var previewTransaction : transactions) {
      var transactionKey = TransactionDuplicateKey.from(previewTransaction).toLookupValue();
      if (existingKeys.contains(transactionKey)) {
        markedTransactions.add(
            previewTransaction.withDuplicate(PreviewDuplicateReason.EXISTING_TRANSACTION));
      } else if (seenKeys.contains(transactionKey)) {
        markedTransactions.add(previewTransaction.withDuplicate(PreviewDuplicateReason.IN_BATCH));
      } else {
        markedTransactions.add(previewTransaction);
      }
      seenKeys.add(transactionKey);
    }

    return markedTransactions;
  }
}
