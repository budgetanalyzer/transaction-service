package org.budgetanalyzer.transaction.service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.domain.ViewCriteria;

/**
 * Internal transaction query criteria shared by transaction search and saved-view matching.
 *
 * <p>This model represents repository query semantics without Spring MVC binding annotations or
 * saved-view persistence concerns.
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
 * @param searchText text to match against transaction descriptions
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
    String searchText,
    Instant createdAfter,
    Instant createdBefore,
    Instant updatedAfter,
    Instant updatedBefore) {

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
    if (filter == null) {
      return empty();
    }

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
   * Creates criteria from saved-view criteria scoped to an authenticated owner.
   *
   * <p>Saved views cannot supply owner IDs directly. The caller injects the authenticated owner ID
   * here. Open-ended views without a stored upper date bound use the current date as the effective
   * upper bound.
   *
   * @param criteria the saved-view criteria
   * @param ownerId the authenticated owner ID
   * @param openEnded whether the saved view should end at the current date when dateTo is absent
   * @return internal transaction criteria
   */
  public static TransactionCriteria fromViewCriteria(
      ViewCriteria criteria, String ownerId, boolean openEnded) {
    var effectiveCriteria = criteria == null ? ViewCriteria.empty() : criteria;
    var effectiveDateTo = effectiveCriteria.dateTo();
    if (openEnded && effectiveDateTo == null) {
      effectiveDateTo = LocalDate.now();
    }

    return new TransactionCriteria(
        null,
        ownerId,
        effectiveCriteria.accountIds(),
        effectiveCriteria.bankNames(),
        effectiveCriteria.dateFrom(),
        effectiveDateTo,
        effectiveCriteria.currencyIsoCodes(),
        effectiveCriteria.minAmount(),
        effectiveCriteria.maxAmount(),
        effectiveCriteria.type(),
        effectiveCriteria.searchText(),
        null,
        null,
        null,
        null);
  }

  private static Set<String> singletonSet(String value) {
    return value == null ? null : Set.of(value);
  }
}
