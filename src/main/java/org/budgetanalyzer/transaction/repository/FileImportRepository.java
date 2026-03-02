package org.budgetanalyzer.transaction.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import org.budgetanalyzer.transaction.domain.FileImport;

/** Repository for {@link FileImport} entities. */
public interface FileImportRepository extends JpaRepository<FileImport, Long> {

  /**
   * Finds a file import record by content hash and user ID.
   *
   * @param contentHash the SHA-256 hash of the file content
   * @param importedBy the user ID who performed the import
   * @return the file import record if found
   */
  Optional<FileImport> findByContentHashAndImportedBy(String contentHash, String importedBy);
}
