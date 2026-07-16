package org.budgetanalyzer.transaction.service.extractor;

import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.service.dto.ParserAttempt;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

/**
 * Interface for extracting transactions from statement files (PDF, CSV, etc.).
 *
 * <p>Implementations attempt one parser revision against the uploaded content. The attempt returns
 * not-applicable for file type or signature mismatches, matched for nonempty preview rows, and
 * failed when a matching parser cannot parse the content or its persisted configuration.
 */
public interface StatementExtractor {

  /**
   * Attempts to extract preview transactions from the uploaded file for one parser revision.
   *
   * @param parserRevision parser revision being attempted
   * @param fileContent the raw file bytes
   * @param filename the original filename (used for extension detection)
   * @param accountId optional account ID to pre-fill for all transactions
   * @return parser attempt outcome
   * @see PreviewTransaction
   */
  ParserAttempt attempt(
      ParserRevision parserRevision, byte[] fileContent, String filename, String accountId);

  /**
   * Returns the internal parser handler identifier.
   *
   * @return internal handler key or generated parser revision key
   */
  String getHandlerKey();
}
