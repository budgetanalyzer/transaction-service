package org.budgetanalyzer.transaction.service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.domain.TransactionType;

/**
 * Internal transaction query criteria for administrative transaction search.
 *
 * <p>This model represents repository query semantics without Spring MVC binding annotations or API
 * binding concerns.
 *
 * @param id the transaction ID to match
 * @param ownerId the owner ID to match
 * @param accountIds account IDs to match
 * @param bankNames bank names to match
 * @param dateFrom inclusive transaction date lower bound
 * @param dateTo inclusive transaction date upper bound
 * @param currencyIsoCodes currency ISO codes to match
 * @param minAmount inclusive amount lower bound
 * @param maxAmount inclusive amount upper bound
 * @param type transaction type to match
 * @param description text to match against transaction descriptions only
 * @param createdAfter inclusive creation timestamp lower bound
 * @param createdBefore inclusive creation timestamp upper bound
 * @param updatedAfter inclusive update timestamp lower bound
 * @param updatedBefore inclusive update timestamp upper bound
 */
public record TransactionCriteria(
    Long id,
    String ownerId,
    Set<String> accountIds,
    Set<String> bankNames,
    LocalDate dateFrom,
    LocalDate dateTo,
    Set<String> currencyIsoCodes,
    BigDecimal minAmount,
    BigDecimal maxAmount,
    TransactionType type,
    String description,
    Instant createdAfter,
    Instant createdBefore,
    Instant updatedAfter,
    Instant updatedBefore) {

  /** Normalizes optional multi-value criteria. */
  public TransactionCriteria {
    accountIds = normalizeValues(accountIds);
    bankNames = normalizeValues(bankNames);
    currencyIsoCodes = normalizeValues(currencyIsoCodes);
  }

  /** Creates an empty criteria with all filters unset. */
  public static TransactionCriteria empty() {
    return new TransactionCriteria(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  /**
   * Creates criteria from transaction search query parameters.
   *
   * <p>Single-value account, bank, and currency filter fields are represented as singleton sets in
   * the internal model.
   *
   * @param filter the transaction search filter
   * @return internal transaction criteria
   */
  public static TransactionCriteria fromFilter(TransactionFilter filter) {
    Objects.requireNonNull(filter, "filter");
    return new TransactionCriteria(
        filter.id(),
        filter.ownerId(),
        singletonSet(filter.accountId()),
        singletonSet(filter.bankName()),
        filter.dateFrom(),
        filter.dateTo(),
        singletonSet(filter.currencyIsoCode()),
        filter.minAmount(),
        filter.maxAmount(),
        filter.type(),
        filter.description(),
        filter.createdAfter(),
        filter.createdBefore(),
        filter.updatedAfter(),
        filter.updatedBefore());
  }

  /**
   * Returns these criteria scoped to the supplied owner.
   *
   * @param ownerId the owner ID to apply
   * @return criteria with the supplied owner and every other filter unchanged
   */
  public TransactionCriteria withOwnerId(String ownerId) {
    return new TransactionCriteria(
        id,
        ownerId,
        accountIds,
        bankNames,
        dateFrom,
        dateTo,
        currencyIsoCodes,
        minAmount,
        maxAmount,
        type,
        description,
        createdAfter,
        createdBefore,
        updatedAfter,
        updatedBefore);
  }

  private static Set<String> singletonSet(String value) {
    return value == null ? null : Set.of(value);
  }

  private static Set<String> normalizeValues(Set<String> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }

    var normalizedValues =
        values.stream()
            .filter(Objects::nonNull)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toUnmodifiableSet());

    return normalizedValues.isEmpty() ? null : normalizedValues;
  }
}
