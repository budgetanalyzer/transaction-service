package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class TransactionDescriptionMatcherTest {

  private final TransactionDescriptionMatcher transactionDescriptionMatcher =
      new TransactionDescriptionMatcher();

  @Test
  void match_matchesObservedMerchantYearlyAndMonthlyDescriptions() {
    var transactionDescriptionMatchResult =
        transactionDescriptionMatcher.match(
            "X CORP. PAID FEATURESBASTROPTX", 42L, "X CORP. PAID FEATURES BASTROP     TX");

    assertThat(transactionDescriptionMatchResult.matched()).isTrue();
    assertThat(transactionDescriptionMatchResult.similarityScore()).isEqualTo(1.0);
    assertThat(transactionDescriptionMatchResult.candidateId()).isEqualTo(42L);
    assertThat(transactionDescriptionMatchResult.candidateDescription())
        .isEqualTo("X CORP. PAID FEATURES BASTROP     TX");
  }

  @Test
  void match_matchesPunctuationAndWhitespaceOnlyVariants() {
    var transactionDescriptionMatchResult =
        transactionDescriptionMatcher.match(
            "Whole-Foods Market #123", 10L, "Whole Foods Market 123");

    assertThat(transactionDescriptionMatchResult.matched()).isTrue();
    assertThat(transactionDescriptionMatchResult.similarityScore()).isEqualTo(1.0);
  }

  @Test
  void match_matchesCaseOnlyVariants() {
    var transactionDescriptionMatchResult =
        transactionDescriptionMatcher.match("monthly subscription", 11L, "MONTHLY SUBSCRIPTION");

    assertThat(transactionDescriptionMatchResult.matched()).isTrue();
    assertThat(transactionDescriptionMatchResult.similarityScore()).isEqualTo(1.0);
  }

  @Test
  void match_matchesHighSimilarityLongDescriptions() {
    var transactionDescriptionMatchResult =
        transactionDescriptionMatcher.match(
            "PAYPAL DIGITAL SERVICES", 12L, "PAYPAL DIGITAL SERVICE");

    assertThat(transactionDescriptionMatchResult.matched()).isTrue();
    assertThat(transactionDescriptionMatchResult.similarityScore()).isGreaterThanOrEqualTo(0.90);
  }

  @Test
  void match_doesNotFuzzyMatchDifferentNumericReferences() {
    var transactionDescriptionMatchResult =
        transactionDescriptionMatcher.match("TRANSFER 1234567890", 16L, "TRANSFER 1234567891");

    assertThat(transactionDescriptionMatchResult.matched()).isFalse();
    assertThat(transactionDescriptionMatchResult.similarityScore()).isZero();
  }

  @Test
  void match_matchesSameNumericReferenceWithPunctuationAndWhitespaceDifferences() {
    var transactionDescriptionMatchResult =
        transactionDescriptionMatcher.match(
            "TRANSFER REF# 1234567890", 17L, "TRANSFER REF 1234567890");

    assertThat(transactionDescriptionMatchResult.matched()).isTrue();
    assertThat(transactionDescriptionMatchResult.similarityScore()).isEqualTo(1.0);
  }

  @Test
  void match_requiresMultipleNumericTokensToMatchInOrder() {
    var transactionDescriptionMatchResult =
        transactionDescriptionMatcher.match(
            "SUBSCRIPTION SERVICE PLAN REF 1 AUTH 2 MONTHLY PAYMENT",
            18L,
            "SUBSCRIPTION SERVICE PLAN REF 2 AUTH 1 MONTHLY PAYMENT");

    assertThat(transactionDescriptionMatchResult.matched()).isFalse();
  }

  @Test
  void match_requiresNumericTokensOnBothDescriptionsForFuzzyMatch() {
    var transactionDescriptionMatchResult =
        transactionDescriptionMatcher.match(
            "PAYPAL DIGITAL SERVICES MONTHLY SUBSCRIPTION REFERENCE 42",
            19L,
            "PAYPAL DIGITAL SERVICES MONTHLY SUBSCRIPTION REFERENCE");

    assertThat(transactionDescriptionMatchResult.matched()).isFalse();
  }

  @Test
  void match_doesNotMatchClearlyDifferentDescriptions() {
    var transactionDescriptionMatchResult =
        transactionDescriptionMatcher.match("RENT PAYMENT MAY", 13L, "STARBUCKS STORE 1234");

    assertThat(transactionDescriptionMatchResult.matched()).isFalse();
    assertThat(transactionDescriptionMatchResult.similarityScore()).isZero();
    assertThat(transactionDescriptionMatchResult.candidateId()).isNull();
    assertThat(transactionDescriptionMatchResult.candidateDescription()).isNull();
  }

  @Test
  void match_doesNotFuzzyMatchVeryShortDescriptions() {
    var transactionDescriptionMatchResult = transactionDescriptionMatcher.match("ABC", 14L, "ABD");

    assertThat(transactionDescriptionMatchResult.matched()).isFalse();
  }

  @Test
  void match_matchesVeryShortDescriptionsOnlyAfterNormalizedExactMatch() {
    var transactionDescriptionMatchResult = transactionDescriptionMatcher.match("A.B.", 15L, "ab");

    assertThat(transactionDescriptionMatchResult.matched()).isTrue();
    assertThat(transactionDescriptionMatchResult.similarityScore()).isEqualTo(1.0);
  }

  @Test
  void normalize_removesPunctuationWhitespaceCaseAndDiacritics() {
    var accentedDescription = " Caf" + Character.toString(0x00E9) + " - Market #42 ";

    assertThat(TransactionDescriptionMatcher.normalize(accentedDescription))
        .isEqualTo("CAFEMARKET42");
  }

  @Test
  void match_requiresDescriptions() {
    assertThatNullPointerException()
        .isThrownBy(() -> transactionDescriptionMatcher.match(null, 1L, "Coffee"))
        .withMessage("incomingDescription");

    assertThatNullPointerException()
        .isThrownBy(() -> transactionDescriptionMatcher.match("Coffee", 1L, null))
        .withMessage("candidateDescription");
  }
}
