package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class TransactionDescriptionMatchResultTest {

  @Test
  void noMatch_createsUnmatchedResultWithoutCandidateMetadata() {
    var transactionDescriptionMatchResult = TransactionDescriptionMatchResult.noMatch();

    assertThat(transactionDescriptionMatchResult.matched()).isFalse();
    assertThat(transactionDescriptionMatchResult.similarityScore()).isZero();
    assertThat(transactionDescriptionMatchResult.candidateId()).isNull();
    assertThat(transactionDescriptionMatchResult.candidateDescription()).isNull();
  }

  @Test
  void match_createsMatchedResultWithCandidateMetadata() {
    var transactionDescriptionMatchResult =
        TransactionDescriptionMatchResult.match(0.92, 42L, "Coffee");

    assertThat(transactionDescriptionMatchResult.matched()).isTrue();
    assertThat(transactionDescriptionMatchResult.similarityScore()).isEqualTo(0.92);
    assertThat(transactionDescriptionMatchResult.candidateId()).isEqualTo(42L);
    assertThat(transactionDescriptionMatchResult.candidateDescription()).isEqualTo("Coffee");
  }

  @Test
  void constructor_rejectsScoresOutsideUnitInterval() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TransactionDescriptionMatchResult(false, -0.01, null, null))
        .withMessage("similarityScore must be between 0.0 and 1.0");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TransactionDescriptionMatchResult(false, 1.01, null, null))
        .withMessage("similarityScore must be between 0.0 and 1.0");
  }
}
