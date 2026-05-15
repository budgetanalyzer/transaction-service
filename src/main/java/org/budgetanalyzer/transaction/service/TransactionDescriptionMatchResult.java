package org.budgetanalyzer.transaction.service;

/**
 * Result of comparing an incoming transaction description to a duplicate candidate description.
 *
 * @param matched whether the descriptions matched under the duplicate rule
 * @param similarityScore the similarity score from 0.0 to 1.0
 * @param candidateId the matched candidate transaction identifier, when available
 * @param candidateDescription the matched candidate description, when available
 */
public record TransactionDescriptionMatchResult(
    boolean matched, double similarityScore, Long candidateId, String candidateDescription) {

  /** Validates match score bounds. */
  public TransactionDescriptionMatchResult {
    if (Double.isNaN(similarityScore) || similarityScore < 0.0 || similarityScore > 1.0) {
      throw new IllegalArgumentException("similarityScore must be between 0.0 and 1.0");
    }
  }

  /** Creates a non-matching description result. */
  public static TransactionDescriptionMatchResult noMatch() {
    return new TransactionDescriptionMatchResult(false, 0.0, null, null);
  }

  /**
   * Creates a matching description result.
   *
   * @param similarityScore the similarity score from 0.0 to 1.0
   * @param candidateId the matched candidate transaction identifier, when available
   * @param candidateDescription the matched candidate description, when available
   * @return the matching description result
   */
  public static TransactionDescriptionMatchResult match(
      double similarityScore, Long candidateId, String candidateDescription) {
    return new TransactionDescriptionMatchResult(
        true, similarityScore, candidateId, candidateDescription);
  }
}
