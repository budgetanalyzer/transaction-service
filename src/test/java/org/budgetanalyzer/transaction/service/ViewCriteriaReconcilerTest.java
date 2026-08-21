package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.domain.ViewCriteria;

class ViewCriteriaReconcilerTest {

  private static final LocalDate EVALUATION_DATE = LocalDate.of(2026, 8, 21);
  private static final LocalDate DATE_FROM = LocalDate.of(2025, 1, 1);
  private static final LocalDate DATE_TO = LocalDate.of(2025, 12, 31);
  private static final BigDecimal MIN_AMOUNT = new BigDecimal("10.00");
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("500.00");

  @Test
  void changedConstraintsReturnsEmptyCriteriaWhenEffectiveCriteriaAreIdentical() {
    var criteria =
        new ViewCriteria(
            DATE_FROM,
            null,
            Set.of("checking", "credit card"),
            Set.of("Capital One", "Bangkok Bank"),
            Set.of("USD", "THB"),
            MIN_AMOUNT,
            MAX_AMOUNT,
            TransactionType.DEBIT,
            "coffee groceries");

    var changedConstraints =
        ViewCriteriaReconciler.changedConstraints(criteria, true, criteria, true, EVALUATION_DATE);

    assertThat(changedConstraints).isEqualTo(ViewCriteria.empty());
  }

  @Test
  void changedConstraintsReturnsOnlyChangedTargetDateFrom() {
    var targetCriteria =
        new ViewCriteria(DATE_FROM, null, null, null, null, null, null, null, null);

    var changedConstraints = changedConstraints(ViewCriteria.empty(), targetCriteria);

    assertThat(changedConstraints)
        .isEqualTo(new ViewCriteria(DATE_FROM, null, null, null, null, null, null, null, null));
  }

  @Test
  void changedConstraintsReturnsOnlyChangedTargetDateTo() {
    var targetCriteria = new ViewCriteria(null, DATE_TO, null, null, null, null, null, null, null);

    var changedConstraints = changedConstraints(ViewCriteria.empty(), targetCriteria);

    assertThat(changedConstraints)
        .isEqualTo(new ViewCriteria(null, DATE_TO, null, null, null, null, null, null, null));
  }

  @Test
  void changedConstraintsReturnsOnlyChangedTargetAccountIds() {
    var targetAccountIds = Set.of("checking-123", "savings-456");
    var targetCriteria =
        new ViewCriteria(null, null, targetAccountIds, null, null, null, null, null, null);

    var changedConstraints = changedConstraints(ViewCriteria.empty(), targetCriteria);

    assertThat(changedConstraints)
        .isEqualTo(
            new ViewCriteria(null, null, targetAccountIds, null, null, null, null, null, null));
  }

  @Test
  void changedConstraintsReturnsOnlyChangedTargetBankNames() {
    var targetBankNames = Set.of("Capital One", "Bangkok Bank");
    var targetCriteria =
        new ViewCriteria(null, null, null, targetBankNames, null, null, null, null, null);

    var changedConstraints = changedConstraints(ViewCriteria.empty(), targetCriteria);

    assertThat(changedConstraints)
        .isEqualTo(
            new ViewCriteria(null, null, null, targetBankNames, null, null, null, null, null));
  }

  @Test
  void changedConstraintsReturnsOnlyChangedTargetCurrencyIsoCodes() {
    var targetCurrencyIsoCodes = Set.of("USD", "THB");
    var targetCriteria =
        new ViewCriteria(null, null, null, null, targetCurrencyIsoCodes, null, null, null, null);

    var changedConstraints = changedConstraints(ViewCriteria.empty(), targetCriteria);

    assertThat(changedConstraints)
        .isEqualTo(
            new ViewCriteria(
                null, null, null, null, targetCurrencyIsoCodes, null, null, null, null));
  }

  @Test
  void changedConstraintsReturnsOnlyChangedTargetMinAmount() {
    var targetCriteria =
        new ViewCriteria(null, null, null, null, null, MIN_AMOUNT, null, null, null);

    var changedConstraints = changedConstraints(ViewCriteria.empty(), targetCriteria);

    assertThat(changedConstraints)
        .isEqualTo(new ViewCriteria(null, null, null, null, null, MIN_AMOUNT, null, null, null));
  }

  @Test
  void changedConstraintsReturnsOnlyChangedTargetMaxAmount() {
    var targetCriteria =
        new ViewCriteria(null, null, null, null, null, null, MAX_AMOUNT, null, null);

    var changedConstraints = changedConstraints(ViewCriteria.empty(), targetCriteria);

    assertThat(changedConstraints)
        .isEqualTo(new ViewCriteria(null, null, null, null, null, null, MAX_AMOUNT, null, null));
  }

  @Test
  void changedConstraintsReturnsOnlyChangedTargetType() {
    var targetCriteria =
        new ViewCriteria(null, null, null, null, null, null, null, TransactionType.CREDIT, null);

    var changedConstraints = changedConstraints(ViewCriteria.empty(), targetCriteria);

    assertThat(changedConstraints)
        .isEqualTo(
            new ViewCriteria(
                null, null, null, null, null, null, null, TransactionType.CREDIT, null));
  }

  @Test
  void changedConstraintsReturnsOnlyChangedTargetSearchText() {
    var targetCriteria =
        new ViewCriteria(null, null, null, null, null, null, null, null, "coffee groceries");

    var changedConstraints = changedConstraints(ViewCriteria.empty(), targetCriteria);

    assertThat(changedConstraints)
        .isEqualTo(
            new ViewCriteria(null, null, null, null, null, null, null, null, "coffee groceries"));
  }

  @Test
  void changedConstraintsDoesNotReapplyRemovedSourceConstraint() {
    var sourceCriteria =
        new ViewCriteria(DATE_FROM, null, null, null, null, null, null, null, null);

    var changedConstraints = changedConstraints(sourceCriteria, ViewCriteria.empty());

    assertThat(changedConstraints).isEqualTo(ViewCriteria.empty());
  }

  @Test
  void changedConstraintsTreatsNullEmptyAndBlankFiltersAsAbsent() {
    var blankAccountIds = new HashSet<String>();
    blankAccountIds.add(null);
    blankAccountIds.add("");
    var blankCurrencyIsoCodes = new HashSet<String>();
    blankCurrencyIsoCodes.add(null);
    blankCurrencyIsoCodes.add(" ");
    var blankBankNames = Set.of("", "\t");
    var sourceCriteria =
        new ViewCriteria(null, null, null, Set.of(), blankCurrencyIsoCodes, null, null, null, null);
    var targetCriteria =
        new ViewCriteria(null, null, blankAccountIds, blankBankNames, null, null, null, null, "  ");

    var changedConstraints = changedConstraints(sourceCriteria, targetCriteria);

    assertThat(changedConstraints).isEqualTo(ViewCriteria.empty());
  }

  @Test
  void changedConstraintsTreatsSetOrderAsEquivalent() {
    var sourceAccountIds = new LinkedHashSet<>(List.of("checking", "savings"));
    var targetAccountIds = new LinkedHashSet<>(List.of("savings", "checking"));
    var sourceBankNames = new LinkedHashSet<>(List.of("Capital", "Bangkok"));
    var targetBankNames = new LinkedHashSet<>(List.of("Bangkok", "Capital"));
    var sourceCurrencyIsoCodes = new LinkedHashSet<>(List.of("USD", "THB"));
    var targetCurrencyIsoCodes = new LinkedHashSet<>(List.of("THB", "USD"));
    var sourceCriteria =
        new ViewCriteria(
            null,
            null,
            sourceAccountIds,
            sourceBankNames,
            sourceCurrencyIsoCodes,
            null,
            null,
            null,
            null);
    var targetCriteria =
        new ViewCriteria(
            null,
            null,
            targetAccountIds,
            targetBankNames,
            targetCurrencyIsoCodes,
            null,
            null,
            null,
            null);

    var changedConstraints = changedConstraints(sourceCriteria, targetCriteria);

    assertThat(changedConstraints).isEqualTo(ViewCriteria.empty());
  }

  @Test
  void changedConstraintsTreatsCaseOnlyDifferencesAsEquivalent() {
    var sourceCriteria =
        new ViewCriteria(
            null,
            null,
            Set.of("CHECKING"),
            Set.of("CAPITAL ONE"),
            Set.of("USD"),
            null,
            null,
            null,
            "COFFEE");
    var targetCriteria =
        new ViewCriteria(
            null,
            null,
            Set.of("checking"),
            Set.of("capital one"),
            Set.of("usd"),
            null,
            null,
            null,
            "coffee");

    var changedConstraints = changedConstraints(sourceCriteria, targetCriteria);

    assertThat(changedConstraints).isEqualTo(ViewCriteria.empty());
  }

  @Test
  void changedConstraintsTreatsEquivalentOrTermsAsUnchanged() {
    var sourceCriteria =
        new ViewCriteria(
            null,
            null,
            Set.of("checking credit"),
            Set.of("Capital One Bangkok"),
            null,
            null,
            null,
            null,
            "coffee    groceries");
    var targetCriteria =
        new ViewCriteria(
            null,
            null,
            Set.of("CREDIT", "CHECKING"),
            Set.of("bangkok", "capital", "one"),
            null,
            null,
            null,
            null,
            " groceries coffee ");

    var changedConstraints = changedConstraints(sourceCriteria, targetCriteria);

    assertThat(changedConstraints).isEqualTo(ViewCriteria.empty());
  }

  @Test
  void changedConstraintsTreatsNumericallyEqualAmountsAsUnchanged() {
    var sourceCriteria =
        new ViewCriteria(
            null,
            null,
            null,
            null,
            null,
            new BigDecimal("10.0"),
            new BigDecimal("500.000"),
            null,
            null);
    var targetCriteria =
        new ViewCriteria(
            null,
            null,
            null,
            null,
            null,
            new BigDecimal("10.00"),
            new BigDecimal("500.0"),
            null,
            null);

    var changedConstraints = changedConstraints(sourceCriteria, targetCriteria);

    assertThat(changedConstraints).isEqualTo(ViewCriteria.empty());
  }

  @Test
  void changedConstraintsAddsEvaluationDateWhenTargetBecomesOpenEnded() {
    var changedConstraints =
        ViewCriteriaReconciler.changedConstraints(
            ViewCriteria.empty(), false, ViewCriteria.empty(), true, EVALUATION_DATE);

    assertThat(changedConstraints)
        .isEqualTo(
            new ViewCriteria(null, EVALUATION_DATE, null, null, null, null, null, null, null));
  }

  @Test
  void changedConstraintsAddsNoDateConstraintWhenTargetStopsBeingOpenEnded() {
    var changedConstraints =
        ViewCriteriaReconciler.changedConstraints(
            ViewCriteria.empty(), true, ViewCriteria.empty(), false, EVALUATION_DATE);

    assertThat(changedConstraints).isEqualTo(ViewCriteria.empty());
  }

  @Test
  void changedConstraintsUsesExplicitDateToInsteadOfOpenEndedDate() {
    var targetCriteria = new ViewCriteria(null, DATE_TO, null, null, null, null, null, null, null);

    var changedConstraints =
        ViewCriteriaReconciler.changedConstraints(
            ViewCriteria.empty(), false, targetCriteria, true, EVALUATION_DATE);

    assertThat(changedConstraints)
        .isEqualTo(new ViewCriteria(null, DATE_TO, null, null, null, null, null, null, null));
  }

  @Test
  void changedConstraintsRetainsAddedTargetValuesAndOmitsUnchangedAndRemovedValues() {
    var sourceCriteria =
        new ViewCriteria(
            DATE_FROM,
            null,
            Set.of("checking"),
            Set.of("Capital One"),
            Set.of("USD"),
            MIN_AMOUNT,
            null,
            TransactionType.DEBIT,
            "coffee");
    var targetBankNames = Set.of("Bangkok Bank");
    var targetCriteria =
        new ViewCriteria(
            DATE_FROM,
            null,
            null,
            targetBankNames,
            Set.of("usd"),
            MIN_AMOUNT,
            MAX_AMOUNT,
            TransactionType.DEBIT,
            "COFFEE");

    var changedConstraints = changedConstraints(sourceCriteria, targetCriteria);

    assertThat(changedConstraints)
        .isEqualTo(
            new ViewCriteria(
                null, null, null, targetBankNames, null, null, MAX_AMOUNT, null, null));
    assertThat(changedConstraints.bankNames()).isSameAs(targetBankNames);
    assertThat(changedConstraints.accountIds()).isNull();
  }

  private static ViewCriteria changedConstraints(
      ViewCriteria sourceCriteria, ViewCriteria targetCriteria) {
    return ViewCriteriaReconciler.changedConstraints(
        sourceCriteria, false, targetCriteria, false, EVALUATION_DATE);
  }
}
