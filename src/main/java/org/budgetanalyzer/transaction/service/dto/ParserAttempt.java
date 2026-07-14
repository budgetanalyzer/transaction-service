package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.ParserRevision;

/** Transient result of trying a parser revision during statement import preview. */
public record ParserAttempt(
    ParserRevision parserRevision,
    ParserAttemptStatus status,
    List<PreviewTransaction> transactions,
    BusinessException failure) {

  /**
   * Creates a non-matching parser attempt.
   *
   * @param parserRevision parser revision that was attempted
   * @return not-applicable attempt
   */
  public static ParserAttempt notApplicable(ParserRevision parserRevision) {
    return new ParserAttempt(parserRevision, ParserAttemptStatus.NOT_APPLICABLE, List.of(), null);
  }

  /**
   * Creates a matched parser attempt.
   *
   * @param parserRevision parser revision that matched
   * @param transactions parsed preview transactions
   * @return matched attempt
   */
  public static ParserAttempt matched(
      ParserRevision parserRevision, List<PreviewTransaction> transactions) {
    return new ParserAttempt(
        parserRevision, ParserAttemptStatus.MATCHED, List.copyOf(transactions), null);
  }

  /**
   * Creates a failed parser attempt.
   *
   * @param parserRevision parser revision that failed
   * @param failure failure raised by the parser
   * @return failed attempt
   */
  public static ParserAttempt failed(ParserRevision parserRevision, BusinessException failure) {
    return new ParserAttempt(parserRevision, ParserAttemptStatus.FAILED, List.of(), failure);
  }
}
