package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class TransactionDescriptionMatcherTest {

  private final TransactionDescriptionMatcher transactionDescriptionMatcher =
      new TransactionDescriptionMatcher();

  @Test
  void match_matchesDescriptionsWithEqualNormalizedForms() {
    assertThat(
            transactionDescriptionMatcher.match(
                "X CORP. PAID FEATURESBASTROPTX", "X CORP. PAID FEATURES BASTROP     TX"))
        .isTrue();
  }

  @Test
  void match_matchesPunctuationAndWhitespaceOnlyVariants() {
    assertThat(
            transactionDescriptionMatcher.match(
                "Whole-Foods Market #123", "Whole Foods Market 123"))
        .isTrue();
  }

  @Test
  void match_matchesCaseOnlyVariants() {
    assertThat(transactionDescriptionMatcher.match("monthly subscription", "MONTHLY SUBSCRIPTION"))
        .isTrue();
  }

  @Test
  void match_doesNotMatchMerelySimilarLongDescriptions() {
    assertThat(
            transactionDescriptionMatcher.match(
                "PAYPAL DIGITAL SERVICES", "PAYPAL DIGITAL SERVICE"))
        .isFalse();
  }

  @Test
  void match_doesNotMatchDifferentNumericReferences() {
    assertThat(transactionDescriptionMatcher.match("TRANSFER 1234567890", "TRANSFER 1234567891"))
        .isFalse();
  }

  @Test
  void match_matchesSameNumericReferenceWithPunctuationAndWhitespaceDifferences() {
    assertThat(
            transactionDescriptionMatcher.match(
                "TRANSFER REF# 1234567890", "TRANSFER REF 1234567890"))
        .isTrue();
  }

  @Test
  void match_doesNotMatchSimilarDescriptionsWithTheSameNumericReference() {
    assertThat(
            transactionDescriptionMatcher.match(
                "PAYPAL DIGITAL SERVICES REF 1234567890 MONTHLY",
                "PAYPAL DIGITAL SERVICE REF 1234567890 MONTHLY"))
        .isFalse();
  }

  @Test
  void match_doesNotMatchWhenNormalizedDescriptionsDifferByOneCharacter() {
    assertThat(
            transactionDescriptionMatcher.match(
                "BILL PAY REF 100 AUTH 55 MONTHLY PAYMENT",
                "BILLPAY REF 100 AUTH 55 MONTHLY PAYMENTS"))
        .isFalse();
  }

  @Test
  void match_doesNotMatchClearlyDifferentDescriptions() {
    assertThat(transactionDescriptionMatcher.match("RENT PAYMENT MAY", "STARBUCKS STORE 1234"))
        .isFalse();
  }

  @Test
  void match_doesNotMatchDifferentVeryShortDescriptions() {
    assertThat(transactionDescriptionMatcher.match("ABC", "ABD")).isFalse();
  }

  @Test
  void match_matchesVeryShortDescriptionsWithEqualNormalizedForms() {
    assertThat(transactionDescriptionMatcher.match("A.B.", "ab")).isTrue();
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
        .isThrownBy(() -> transactionDescriptionMatcher.match(null, "Coffee"))
        .withMessage("incomingDescription");

    assertThatNullPointerException()
        .isThrownBy(() -> transactionDescriptionMatcher.match("Coffee", null))
        .withMessage("candidateDescription");
  }
}
