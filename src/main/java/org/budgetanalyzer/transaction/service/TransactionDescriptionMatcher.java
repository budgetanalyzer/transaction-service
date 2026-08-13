package org.budgetanalyzer.transaction.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/** Matches transaction descriptions using normalized equality. */
final class TransactionDescriptionMatcher {

  boolean matches(String incomingDescription, String candidateDescription) {
    Objects.requireNonNull(incomingDescription, "incomingDescription");
    Objects.requireNonNull(candidateDescription, "candidateDescription");

    var normalizedIncomingDescription = normalize(incomingDescription);
    var normalizedCandidateDescription = normalize(candidateDescription);
    return normalizedIncomingDescription.equals(normalizedCandidateDescription);
  }

  static String normalize(String description) {
    Objects.requireNonNull(description, "description");

    var normalizedDescription =
        Normalizer.normalize(description.trim(), Normalizer.Form.NFKD).toUpperCase(Locale.ROOT);
    var normalizedDescriptionBuilder = new StringBuilder(normalizedDescription.length());

    normalizedDescription
        .codePoints()
        .filter(TransactionDescriptionMatcher::isComparableCodePoint)
        .forEach(normalizedDescriptionBuilder::appendCodePoint);

    return normalizedDescriptionBuilder.toString();
  }

  private static boolean isComparableCodePoint(int codePoint) {
    return Character.isLetterOrDigit(codePoint);
  }
}
