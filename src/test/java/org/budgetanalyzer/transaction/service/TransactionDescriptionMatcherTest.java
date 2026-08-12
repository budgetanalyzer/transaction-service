package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class TransactionDescriptionMatcherTest {

  private final TransactionDescriptionMatcher transactionDescriptionMatcher =
      new TransactionDescriptionMatcher();

  @Test
  void shouldMatchDescriptionsWhenNormalizedFormsAreEqual() {
    assertThat(
            transactionDescriptionMatcher.matches(
                "X CORP. PAID FEATURESBASTROPTX", "X CORP. PAID FEATURES BASTROP     TX"))
        .isTrue();
  }

  @Test
  void match_matchesPunctuationAndWhitespaceOnlyVariants() {
    assertThat(
            transactionDescriptionMatcher.matches(
                "Whole-Foods Market #123", "Whole Foods Market 123"))
        .isTrue();
  }

  @Test
  void match_matchesCaseOnlyVariants() {
    assertThat(
            transactionDescriptionMatcher.matches("monthly subscription", "MONTHLY SUBSCRIPTION"))
        .isTrue();
  }

  @Test
  void shouldNotMatchMerelySimilarLongDescriptions() {
    assertThat(
            transactionDescriptionMatcher.matches(
                "PAYPAL DIGITAL SERVICES", "PAYPAL DIGITAL SERVICE"))
        .isFalse();
  }

  @Test
  void shouldNotMatchDescriptionsWhenNumericReferencesDiffer() {
    assertThat(transactionDescriptionMatcher.matches("TRANSFER 1234567890", "TRANSFER 1234567891"))
        .isFalse();
  }

  @Test
  void match_matchesSameNumericReferenceWithPunctuationAndWhitespaceDifferences() {
    assertThat(
            transactionDescriptionMatcher.matches(
                "TRANSFER REF# 1234567890", "TRANSFER REF 1234567890"))
        .isTrue();
  }

  @Test
  void shouldNotMatchMerelySimilarDescriptionsWithSameNumericReference() {
    assertThat(
            transactionDescriptionMatcher.matches(
                "PAYPAL DIGITAL SERVICES REF 1234567890 MONTHLY",
                "PAYPAL DIGITAL SERVICE REF 1234567890 MONTHLY"))
        .isFalse();
  }

  @Test
  void shouldNotMatchWhenNormalizedDescriptionsDifferByOneCharacter() {
    assertThat(
            transactionDescriptionMatcher.matches(
                "BILL PAY REF 100 AUTH 55 MONTHLY PAYMENT",
                "BILLPAY REF 100 AUTH 55 MONTHLY PAYMENTS"))
        .isFalse();
  }

  @Test
  void match_doesNotMatchClearlyDifferentDescriptions() {
    assertThat(transactionDescriptionMatcher.matches("RENT PAYMENT MAY", "STARBUCKS STORE 1234"))
        .isFalse();
  }

  @Test
  void shouldNotMatchVeryShortDescriptionsWhenTheyDiffer() {
    assertThat(transactionDescriptionMatcher.matches("ABC", "ABD")).isFalse();
  }

  @Test
  void shouldMatchVeryShortDescriptionsWhenNormalizedFormsAreEqual() {
    assertThat(transactionDescriptionMatcher.matches("A.B.", "ab")).isTrue();
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
        .isThrownBy(() -> transactionDescriptionMatcher.matches(null, "Coffee"))
        .withMessage("incomingDescription");

    assertThatNullPointerException()
        .isThrownBy(() -> transactionDescriptionMatcher.matches("Coffee", null))
        .withMessage("candidateDescription");
  }
}
