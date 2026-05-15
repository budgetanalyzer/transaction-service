package org.budgetanalyzer.transaction.service.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.domain.ViewCriteria;

class TransactionCriteriaTest {

  private static final String USER_ID = "usr_test123";

  @Test
  void fromFilter_mapsSingleValueFieldsToSingletonSets() {
    var createdAfter = Instant.parse("2025-01-01T00:00:00Z");
    var createdBefore = Instant.parse("2025-01-02T00:00:00Z");
    var updatedAfter = Instant.parse("2025-01-03T00:00:00Z");
    var updatedBefore = Instant.parse("2025-01-04T00:00:00Z");
    var filter =
        new TransactionFilter(
            42L,
            USER_ID,
            "checking-123",
            "Capital One",
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            "USD",
            BigDecimal.TEN,
            BigDecimal.valueOf(100),
            TransactionType.DEBIT,
            "coffee",
            createdAfter,
            createdBefore,
            updatedAfter,
            updatedBefore);

    var criteria = TransactionCriteria.fromFilter(filter);

    assertThat(criteria.id()).isEqualTo(42L);
    assertThat(criteria.ownerId()).isEqualTo(USER_ID);
    assertThat(criteria.accountIds()).containsExactly("checking-123");
    assertThat(criteria.bankNames()).containsExactly("Capital One");
    assertThat(criteria.dateFrom()).isEqualTo(LocalDate.of(2024, 12, 1));
    assertThat(criteria.dateTo()).isEqualTo(LocalDate.of(2024, 12, 31));
    assertThat(criteria.currencyIsoCodes()).containsExactly("USD");
    assertThat(criteria.minAmount()).isEqualByComparingTo("10");
    assertThat(criteria.maxAmount()).isEqualByComparingTo("100");
    assertThat(criteria.type()).isEqualTo(TransactionType.DEBIT);
    assertThat(criteria.description()).isEqualTo("coffee");
    assertThat(criteria.searchText()).isNull();
    assertThat(criteria.createdAfter()).isEqualTo(createdAfter);
    assertThat(criteria.createdBefore()).isEqualTo(createdBefore);
    assertThat(criteria.updatedAfter()).isEqualTo(updatedAfter);
    assertThat(criteria.updatedBefore()).isEqualTo(updatedBefore);
  }

  @Test
  void fromFilter_dropsBlankSingletonFilterValues() {
    var filter =
        new TransactionFilter(
            null, null, " ", "", null, null, "\t", null, null, null, null, null, null, null, null);

    var criteria = TransactionCriteria.fromFilter(filter);

    assertThat(criteria.accountIds()).isNull();
    assertThat(criteria.bankNames()).isNull();
    assertThat(criteria.currencyIsoCodes()).isNull();
  }

  @Test
  void fromViewCriteria_mapsSavedViewFieldsAndInjectedOwner() {
    var viewCriteria =
        new ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            Set.of("checking-123", "savings-456"),
            Set.of("Capital One", "Bangkok Bank"),
            Set.of("USD", "THB"),
            BigDecimal.TEN,
            BigDecimal.valueOf(100),
            TransactionType.DEBIT,
            "coffee");

    var criteria = TransactionCriteria.fromViewCriteria(viewCriteria, USER_ID, false);

    assertThat(criteria.id()).isNull();
    assertThat(criteria.ownerId()).isEqualTo(USER_ID);
    assertThat(criteria.accountIds()).containsExactlyInAnyOrder("checking-123", "savings-456");
    assertThat(criteria.bankNames()).containsExactlyInAnyOrder("Capital One", "Bangkok Bank");
    assertThat(criteria.dateFrom()).isEqualTo(LocalDate.of(2024, 12, 1));
    assertThat(criteria.dateTo()).isEqualTo(LocalDate.of(2024, 12, 31));
    assertThat(criteria.currencyIsoCodes()).containsExactlyInAnyOrder("USD", "THB");
    assertThat(criteria.minAmount()).isEqualByComparingTo("10");
    assertThat(criteria.maxAmount()).isEqualByComparingTo("100");
    assertThat(criteria.type()).isEqualTo(TransactionType.DEBIT);
    assertThat(criteria.description()).isNull();
    assertThat(criteria.searchText()).isEqualTo("coffee");
    assertThat(criteria.createdAfter()).isNull();
    assertThat(criteria.createdBefore()).isNull();
    assertThat(criteria.updatedAfter()).isNull();
    assertThat(criteria.updatedBefore()).isNull();
  }

  @Test
  void fromViewCriteria_openEndedWithoutDateToUsesCurrentDate() {
    var viewCriteria = new ViewCriteria(null, null, null, null, null, null, null, null, null);

    var criteria = TransactionCriteria.fromViewCriteria(viewCriteria, USER_ID, true);

    assertThat(criteria.dateTo()).isEqualTo(LocalDate.now());
  }

  @Test
  void fromViewCriteria_dropsNullBlankAndEmptySetValues() {
    var accountIds = new java.util.HashSet<String>();
    accountIds.add("checking-123");
    accountIds.add("");
    accountIds.add(null);
    var bankNames = Set.of("Capital One", " ");
    var viewCriteria =
        new ViewCriteria(null, null, accountIds, bankNames, Set.of(), null, null, null, null);

    var criteria = TransactionCriteria.fromViewCriteria(viewCriteria, USER_ID, false);

    assertThat(criteria.accountIds()).containsExactly("checking-123");
    assertThat(criteria.bankNames()).containsExactly("Capital One");
    assertThat(criteria.currencyIsoCodes()).isNull();
  }
}
