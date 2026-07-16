package org.budgetanalyzer.transaction.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.repository.FileImportRepository;

/** Service for tracking imported files and exact reupload status. */
@Service
public class FileImportTrackingService {

  private static final Logger log = LoggerFactory.getLogger(FileImportTrackingService.class);

  private final FileImportRepository fileImportRepository;
  private final FileHashService fileHashService;

  /**
   * Constructs a new FileImportTrackingService.
   *
   * @param fileImportRepository the repository for file import records
   * @param fileHashService the service for computing file hashes
   */
  public FileImportTrackingService(
      FileImportRepository fileImportRepository, FileHashService fileHashService) {
    this.fileImportRepository = fileImportRepository;
    this.fileHashService = fileHashService;
  }

  /**
   * Checks if already-read file content has already been imported by the specified user.
   *
   * @param fileContent the file content to check
   * @param userId the user ID to check against
   * @return file hash and existing import record (if any)
   */
  public FileCheckResult checkFile(byte[] fileContent, String userId) {
    var hash = fileHashService.computeHash(fileContent);
    return checkHash(hash, userId);
  }

  /**
   * Checks if a content hash has already been imported by the specified user.
   *
   * @param hash the SHA-256 file content hash
   * @param userId the user ID to check against
   * @return file hash and existing import record (if any)
   */
  public FileCheckResult checkHash(String hash, String userId) {
    var existingImport = fileImportRepository.findByContentHashAndImportedBy(hash, userId);
    return new FileCheckResult(hash, existingImport);
  }

  /**
   * Records a successful file import.
   *
   * @param contentHash the SHA-256 hash of the file content
   * @param originalFilename the original filename
   * @param statementFormatId the selected statement format ID
   * @param parserRevisionId the selected parser revision ID
   * @param accountId the account ID (nullable)
   * @param fileSizeBytes the file size in bytes
   * @param transactionCount the number of transactions imported
   * @param importedBy the user ID who performed the import
   * @return the created file import record
   */
  public FileImport recordImport(
      String contentHash,
      String originalFilename,
      Long statementFormatId,
      Long parserRevisionId,
      String accountId,
      Long fileSizeBytes,
      Integer transactionCount,
      String importedBy) {
    var fileImport =
        FileImport.create(
            contentHash,
            originalFilename,
            statementFormatId,
            parserRevisionId,
            accountId,
            fileSizeBytes,
            transactionCount,
            importedBy);

    log.info(
        "Recording file import: statementFormatId={} parserRevisionId={} transactions={}",
        statementFormatId,
        parserRevisionId,
        transactionCount);

    return fileImportRepository.save(fileImport);
  }

  /** Result of checking a file for prior exact import by the same user. */
  public record FileCheckResult(String hash, Optional<FileImport> existingImport) {}
}
