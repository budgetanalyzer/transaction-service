package org.budgetanalyzer.transaction.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.budgetanalyzer.transaction.domain.ViewCriteria;

/** Reconciles saved-view criteria according to their effective transaction-matching semantics. */
public final class ViewCriteriaReconciler {

  private ViewCriteriaReconciler() {}

  /**
   * Returns the effective target constraints that differ from the source constraints.
   *
   * <p>An unchanged or removed target constraint is represented by {@code null}. When an open-ended
   * view has no explicit upper date bound, {@code evaluationDate} is its effective {@code dateTo}
   * value.
   *
   * @param sourceCriteria the source saved-view criteria
   * @param sourceOpenEnded whether the source view is open-ended
   * @param targetCriteria the complete target saved-view criteria
   * @param targetOpenEnded whether the target view is open-ended
   * @param evaluationDate the date used to resolve open-ended upper bounds
   * @return target constraints whose effective values changed
   */
  public static ViewCriteria changedConstraints(
      ViewCriteria sourceCriteria,
      boolean sourceOpenEnded,
      ViewCriteria targetCriteria,
      boolean targetOpenEnded,
      LocalDate evaluationDate) {
    Objects.requireNonNull(sourceCriteria, "sourceCriteria");
    Objects.requireNonNull(targetCriteria, "targetCriteria");
    Objects.requireNonNull(evaluationDate, "evaluationDate");
    var sourceDateTo = effectiveDateTo(sourceCriteria, sourceOpenEnded, evaluationDate);
    var targetDateTo = effectiveDateTo(targetCriteria, targetOpenEnded, evaluationDate);

    return new ViewCriteria(
        changedTarget(
            targetCriteria.dateFrom(),
            Objects.equals(sourceCriteria.dateFrom(), targetCriteria.dateFrom())),
        changedTarget(targetDateTo, Objects.equals(sourceDateTo, targetDateTo)),
        changedTarget(
            targetCriteria.accountIds(),
            equivalentOrTermSets(sourceCriteria.accountIds(), targetCriteria.accountIds())),
        changedTarget(
            targetCriteria.bankNames(),
            equivalentOrTermSets(sourceCriteria.bankNames(), targetCriteria.bankNames())),
        changedTarget(
            targetCriteria.currencyIsoCodes(),
            equivalentExactSets(
                sourceCriteria.currencyIsoCodes(), targetCriteria.currencyIsoCodes())),
        changedTarget(
            targetCriteria.minAmount(),
            equivalentAmounts(sourceCriteria.minAmount(), targetCriteria.minAmount())),
        changedTarget(
            targetCriteria.maxAmount(),
            equivalentAmounts(sourceCriteria.maxAmount(), targetCriteria.maxAmount())),
        changedTarget(
            targetCriteria.type(), Objects.equals(sourceCriteria.type(), targetCriteria.type())),
        changedTarget(
            targetCriteria.searchText(),
            equivalentOrTerms(sourceCriteria.searchText(), targetCriteria.searchText())));
  }

  private static <T> T changedTarget(T targetValue, boolean equivalent) {
    return equivalent ? null : targetValue;
  }

  private static LocalDate effectiveDateTo(
      ViewCriteria criteria, boolean openEnded, LocalDate evaluationDate) {
    if (criteria.dateTo() != null) {
      return criteria.dateTo();
    }
    return openEnded ? evaluationDate : null;
  }

  private static boolean equivalentAmounts(BigDecimal sourceAmount, BigDecimal targetAmount) {
    return sourceAmount == null
        ? targetAmount == null
        : targetAmount != null && sourceAmount.compareTo(targetAmount) == 0;
  }

  private static boolean equivalentExactSets(Set<String> sourceValues, Set<String> targetValues) {
    return canonicalExactValues(sourceValues).equals(canonicalExactValues(targetValues));
  }

  private static Set<String> canonicalExactValues(Set<String> values) {
    if (values == null) {
      return Set.of();
    }

    return values.stream()
        .filter(Objects::nonNull)
        .filter(value -> !value.isBlank())
        .map(value -> value.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static boolean equivalentOrTermSets(Set<String> sourceValues, Set<String> targetValues) {
    return canonicalOrTerms(sourceValues).equals(canonicalOrTerms(targetValues));
  }

  private static Set<String> canonicalOrTerms(Set<String> values) {
    return values == null ? Set.of() : canonicalOrTerms(values.stream());
  }

  private static Set<String> canonicalOrTerms(String value) {
    return value == null ? Set.of() : canonicalOrTerms(Stream.of(value));
  }

  private static Set<String> canonicalOrTerms(Stream<String> values) {
    return values
        .filter(Objects::nonNull)
        .filter(value -> !value.isBlank())
        .flatMap(value -> Arrays.stream(value.trim().split("\\s+")))
        .map(value -> value.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static boolean equivalentOrTerms(String sourceValue, String targetValue) {
    return canonicalOrTerms(sourceValue).equals(canonicalOrTerms(targetValue));
  }
}
