package org.budgetanalyzer.transaction.service.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.domain.TransactionType;

class TransactionCriteriaTest {

  private static final String USER_ID = "usr_test123";

  @Test
  void fromFilterMapsSingleValueFieldsToSingletonSets() {
    var createdAfter = Instant.parse("2025-01-01T00:00:00Z");
    var createdBefore = Instant.parse("2025-01-02T00:00:00Z");
    var updatedAfter = Instant.parse("2025-01-03T00:00:00Z");
    var updatedBefore = Instant.parse("2025-01-04T00:00:00Z");
    var transactionFilter =
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

    var transactionCriteria = TransactionCriteria.fromFilter(transactionFilter);

    assertThat(transactionCriteria.id()).isEqualTo(42L);
    assertThat(transactionCriteria.ownerId()).isEqualTo(USER_ID);
    assertThat(transactionCriteria.accountIds()).containsExactly("checking-123");
    assertThat(transactionCriteria.bankNames()).containsExactly("Capital One");
    assertThat(transactionCriteria.currencyIsoCodes()).containsExactly("USD");
    assertThat(transactionCriteria.description()).isEqualTo("coffee");
    assertThat(transactionCriteria.createdAfter()).isEqualTo(createdAfter);
    assertThat(transactionCriteria.createdBefore()).isEqualTo(createdBefore);
    assertThat(transactionCriteria.updatedAfter()).isEqualTo(updatedAfter);
    assertThat(transactionCriteria.updatedBefore()).isEqualTo(updatedBefore);
  }

  @Test
  void fromFilterDropsBlankSingletonFilterValues() {
    var transactionFilter =
        new TransactionFilter(
            null, null, " ", "", null, null, "\t", null, null, null, null, null, null, null, null);

    var transactionCriteria = TransactionCriteria.fromFilter(transactionFilter);

    assertThat(transactionCriteria.accountIds()).isNull();
    assertThat(transactionCriteria.bankNames()).isNull();
    assertThat(transactionCriteria.currencyIsoCodes()).isNull();
  }
}
