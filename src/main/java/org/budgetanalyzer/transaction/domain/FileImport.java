package org.budgetanalyzer.transaction.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

/**
 * Entity tracking imported CSV files to prevent duplicate imports.
 *
 * <p>Each successful file import creates a record with the SHA-256 hash of the file content.
 * Duplicate detection is per-user: the same file content can be imported by different users, but
 * the same user cannot import the same file twice.
 */
@Entity
@Table(name = "file_import")
public class FileImport {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** SHA-256 hash of the file content (hex encoded, 64 characters). */
  @NotNull
  @Column(name = "content_hash", length = 64, nullable = false)
  private String contentHash;

  /** Original filename as provided during import. */
  @NotNull
  @Column(name = "original_filename", nullable = false)
  private String originalFilename;

  /** Format key (e.g., "capital-one-bank-csv", "bkk-bank-csv"). */
  @NotNull
  @Column(name = "format", length = 50, nullable = false)
  private String format;

  /** Account ID specified during import (nullable). */
  @Column(name = "account_id")
  private String accountId;

  /** File size in bytes. */
  @NotNull
  @Column(name = "file_size_bytes", nullable = false)
  private Long fileSizeBytes;

  /** Number of transactions imported from this file. */
  @NotNull
  @Column(name = "transaction_count", nullable = false)
  private Integer transactionCount;

  /** User ID who performed the import. */
  @NotNull
  @Column(name = "imported_by", length = 50, nullable = false)
  private String importedBy;

  /** Timestamp when import completed. */
  @NotNull
  @Column(name = "imported_at", nullable = false)
  private Instant importedAt;

  /** Default constructor for JPA. */
  protected FileImport() {}

  /**
   * Creates a new FileImport record.
   *
   * @param contentHash SHA-256 hash of the file content
   * @param originalFilename original filename as uploaded
   * @param format CSV format key
   * @param accountId account ID (nullable)
   * @param fileSizeBytes file size in bytes
   * @param transactionCount number of transactions imported
   * @param importedBy user ID who performed the import
   * @return new FileImport instance
   */
  public static FileImport create(
      String contentHash,
      String originalFilename,
      String format,
      String accountId,
      Long fileSizeBytes,
      Integer transactionCount,
      String importedBy) {
    var fileImport = new FileImport();
    fileImport.contentHash = contentHash;
    fileImport.originalFilename = originalFilename;
    fileImport.format = format;
    fileImport.accountId = accountId;
    fileImport.fileSizeBytes = fileSizeBytes;
    fileImport.transactionCount = transactionCount;
    fileImport.importedBy = importedBy;
    fileImport.importedAt = Instant.now();
    return fileImport;
  }

  public Long getId() {
    return id;
  }

  public String getContentHash() {
    return contentHash;
  }

  public String getOriginalFilename() {
    return originalFilename;
  }

  public String getFormat() {
    return format;
  }

  public String getAccountId() {
    return accountId;
  }

  public Long getFileSizeBytes() {
    return fileSizeBytes;
  }

  public Integer getTransactionCount() {
    return transactionCount;
  }

  public String getImportedBy() {
    return importedBy;
  }

  public Instant getImportedAt() {
    return importedAt;
  }
}
