package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractor;

/** Transient result of trying a parser revision during statement import preview. */
public record ParserAttempt(
    ParserRevision parserRevision,
    ParserAttemptStatus status,
    StatementExtractor statementExtractor,
    List<PreviewTransaction> transactions,
    String diagnostic,
    BusinessException failure) {

  /**
   * Creates a non-matching parser attempt.
   *
   * @param parserRevision parser revision that was attempted
   * @param diagnostic sanitized diagnostic text
   * @return not-applicable attempt
   */
  public static ParserAttempt notApplicable(ParserRevision parserRevision, String diagnostic) {
    return new ParserAttempt(
        parserRevision, ParserAttemptStatus.NOT_APPLICABLE, null, List.of(), diagnostic, null);
  }

  /**
   * Creates a matched parser attempt.
   *
   * @param parserRevision parser revision that matched
   * @param statementExtractor extractor that parsed the file
   * @param transactions parsed preview transactions
   * @return matched attempt
   */
  public static ParserAttempt matched(
      ParserRevision parserRevision,
      StatementExtractor statementExtractor,
      List<PreviewTransaction> transactions) {
    return new ParserAttempt(
        parserRevision,
        ParserAttemptStatus.MATCHED,
        statementExtractor,
        List.copyOf(transactions),
        null,
        null);
  }

  /**
   * Creates a failed parser attempt.
   *
   * @param parserRevision parser revision that failed
   * @param diagnostic sanitized diagnostic text
   * @param failure failure raised by the parser
   * @return failed attempt
   */
  public static ParserAttempt failed(
      ParserRevision parserRevision, String diagnostic, BusinessException failure) {
    return new ParserAttempt(
        parserRevision, ParserAttemptStatus.FAILED, null, List.of(), diagnostic, failure);
  }
}
