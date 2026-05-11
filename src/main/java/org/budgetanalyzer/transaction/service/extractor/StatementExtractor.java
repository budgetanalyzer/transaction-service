package org.budgetanalyzer.transaction.service.extractor;

import java.util.List;

import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

/**
 * Interface for extracting transactions from statement files (PDF, CSV, etc.).
 *
 * <p>Implementations detect whether they can handle a given file and extract transactions into a
 * standardized format for preview before import.
 */
public interface StatementExtractor {

  /**
   * Determines if this extractor can handle the given file.
   *
   * @param fileContent the raw file bytes
   * @param filename the original filename (used for extension detection)
   * @return true if this extractor can process the file
   */
  boolean canHandle(byte[] fileContent, String filename);

  /**
   * Extracts transactions from the file content for preview.
   *
   * @param fileContent the raw file bytes
   * @param accountId optional account ID to pre-fill for all transactions
   * @return extracted preview transactions
   */
  List<PreviewTransaction> extract(byte[] fileContent, String accountId);

  /**
   * Extracts transactions as entities for batch import.
   *
   * @param fileContent the raw file bytes
   * @param accountId optional account ID to pre-fill for all transactions
   * @param fileImport the file import record to link transactions to
   * @return list of Transaction entities ready for persistence
   */
  List<Transaction> extractEntities(byte[] fileContent, String accountId, FileImport fileImport);

  /**
   * Returns the format identifier for this extractor.
   *
   * @return format key (e.g., "capital-one-yearly")
   */
  String getFormatKey();
}
