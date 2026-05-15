package org.budgetanalyzer.transaction.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/** Matches transaction descriptions using normalized exact and conservative fuzzy comparison. */
final class TransactionDescriptionMatcher {

  /*
   * Keep the first fuzzy pass conservative: exact financial fields already narrow the candidate
   * set, but descriptions such as short merchant acronyms still need a high bar to avoid false
   * positives.
   */
  private static final double FUZZY_MATCH_THRESHOLD = 0.90;
  private static final int MINIMUM_FUZZY_MATCH_LENGTH = 8;

  TransactionDescriptionMatchResult match(
      String incomingDescription, Long candidateId, String candidateDescription) {
    Objects.requireNonNull(incomingDescription, "incomingDescription");
    Objects.requireNonNull(candidateDescription, "candidateDescription");

    var normalizedIncomingDescription = normalize(incomingDescription);
    var normalizedCandidateDescription = normalize(candidateDescription);

    if (normalizedIncomingDescription.equals(normalizedCandidateDescription)) {
      return TransactionDescriptionMatchResult.match(1.0, candidateId, candidateDescription);
    }

    if (!canFuzzyMatch(normalizedIncomingDescription, normalizedCandidateDescription)) {
      return TransactionDescriptionMatchResult.noMatch();
    }

    var similarityScore =
        calculateNormalizedLevenshteinSimilarity(
            normalizedIncomingDescription, normalizedCandidateDescription);
    if (similarityScore >= FUZZY_MATCH_THRESHOLD) {
      return TransactionDescriptionMatchResult.match(
          similarityScore, candidateId, candidateDescription);
    }

    return TransactionDescriptionMatchResult.noMatch();
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

  private static boolean canFuzzyMatch(
      String normalizedIncomingDescription, String normalizedCandidateDescription) {
    return normalizedIncomingDescription.length() >= MINIMUM_FUZZY_MATCH_LENGTH
        && normalizedCandidateDescription.length() >= MINIMUM_FUZZY_MATCH_LENGTH;
  }

  private static boolean isComparableCodePoint(int codePoint) {
    return Character.isLetterOrDigit(codePoint)
        && Character.getType(codePoint) != Character.NON_SPACING_MARK;
  }

  private static double calculateNormalizedLevenshteinSimilarity(
      String normalizedIncomingDescription, String normalizedCandidateDescription) {
    var incomingDescriptionCodePoints = normalizedIncomingDescription.codePoints().toArray();
    var candidateDescriptionCodePoints = normalizedCandidateDescription.codePoints().toArray();
    var maximumLength =
        Math.max(incomingDescriptionCodePoints.length, candidateDescriptionCodePoints.length);
    if (maximumLength == 0) {
      return 1.0;
    }

    var levenshteinDistance =
        calculateLevenshteinDistance(incomingDescriptionCodePoints, candidateDescriptionCodePoints);
    return 1.0 - ((double) levenshteinDistance / maximumLength);
  }

  private static int calculateLevenshteinDistance(
      int[] incomingDescriptionCodePoints, int[] candidateDescriptionCodePoints) {
    var previousDistances = new int[candidateDescriptionCodePoints.length + 1];
    var currentDistances = new int[candidateDescriptionCodePoints.length + 1];

    for (int candidateIndex = 0;
        candidateIndex <= candidateDescriptionCodePoints.length;
        candidateIndex++) {
      previousDistances[candidateIndex] = candidateIndex;
    }

    for (int incomingIndex = 1;
        incomingIndex <= incomingDescriptionCodePoints.length;
        incomingIndex++) {
      currentDistances[0] = incomingIndex;

      for (int candidateIndex = 1;
          candidateIndex <= candidateDescriptionCodePoints.length;
          candidateIndex++) {
        var substitutionCost =
            incomingDescriptionCodePoints[incomingIndex - 1]
                    == candidateDescriptionCodePoints[candidateIndex - 1]
                ? 0
                : 1;
        currentDistances[candidateIndex] =
            Math.min(
                Math.min(
                    currentDistances[candidateIndex - 1] + 1,
                    previousDistances[candidateIndex] + 1),
                previousDistances[candidateIndex - 1] + substitutionCost);
      }

      var nextPreviousDistances = previousDistances;
      previousDistances = currentDistances;
      currentDistances = nextPreviousDistances;
    }

    return previousDistances[candidateDescriptionCodePoints.length];
  }
}
