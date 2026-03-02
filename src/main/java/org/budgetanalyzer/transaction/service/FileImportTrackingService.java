package org.budgetanalyzer.transaction.service;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.repository.FileImportRepository;

/** Service for tracking imported files and detecting duplicates. */
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
   * Checks if a file has already been imported by the specified user.
   *
   * @param file the file to check
   * @param userId the user ID to check against
   * @return file hash and existing import record (if any)
   * @throws IOException if reading the file fails
   */
  public FileCheckResult checkFile(MultipartFile file, String userId) throws IOException {
    var hash = fileHashService.computeHash(file);
    var existingImport = fileImportRepository.findByContentHashAndImportedBy(hash, userId);
    return new FileCheckResult(hash, existingImport);
  }

  /**
   * Checks file and throws exception if already imported by the user.
   *
   * @param file the file to check
   * @param userId the user ID to check against
   * @return the computed file hash
   * @throws BusinessException if file was already imported by this user
   * @throws IOException if reading the file fails
   */
  public String checkAndRejectDuplicate(MultipartFile file, String userId) throws IOException {
    var result = checkFile(file, userId);
    if (result.existingImport().isPresent()) {
      var existing = result.existingImport().get();
      log.warn(
          "Duplicate file detected: '{}' matches previously imported file '{}' from {}",
          file.getOriginalFilename(),
          existing.getOriginalFilename(),
          existing.getImportedAt());

      throw new BusinessException(
          String.format(
              "File '%s' has already been imported on %s (original filename: '%s', %d "
                  + "transactions). The same file content cannot be imported twice.",
              file.getOriginalFilename(),
              existing.getImportedAt(),
              existing.getOriginalFilename(),
              existing.getTransactionCount()),
          BudgetAnalyzerError.FILE_ALREADY_IMPORTED.name());
    }
    return result.hash();
  }

  /**
   * Records a successful file import.
   *
   * @param contentHash the SHA-256 hash of the file content
   * @param originalFilename the original filename
   * @param format the CSV format used
   * @param accountId the account ID (nullable)
   * @param fileSizeBytes the file size in bytes
   * @param transactionCount the number of transactions imported
   * @param importedBy the user ID who performed the import
   * @return the created file import record
   */
  public FileImport recordImport(
      String contentHash,
      String originalFilename,
      String format,
      String accountId,
      Long fileSizeBytes,
      Integer transactionCount,
      String importedBy) {
    var fileImport =
        FileImport.create(
            contentHash,
            originalFilename,
            format,
            accountId,
            fileSizeBytes,
            transactionCount,
            importedBy);

    log.info(
        "Recording file import: filename='{}' hash='{}' format='{}' transactions={}",
        originalFilename,
        contentHash.substring(0, 8) + "...",
        format,
        transactionCount);

    return fileImportRepository.save(fileImport);
  }

  /** Result of checking a file for duplicate import. */
  public record FileCheckResult(String hash, Optional<FileImport> existingImport) {}
}
