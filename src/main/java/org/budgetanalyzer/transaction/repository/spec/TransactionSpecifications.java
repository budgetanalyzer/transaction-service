package org.budgetanalyzer.transaction.repository.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import org.springframework.data.jpa.domain.Specification;

import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.service.dto.TransactionCriteria;

/** Specification builder for {@link Transaction} queries based on {@link TransactionCriteria}. */
public class TransactionSpecifications {

  private static final char ESCAPE_CHAR = '\\';

  /**
   * Builds a JPA {@link Specification} for filtering {@link Transaction} entities using internal
   * query criteria.
   *
   * <p>All non-null fields in the criteria are converted into predicates. For text fields, {@code
   * LIKE} operations (case-insensitive) are used with the following behavior:
   *
   * <ul>
   *   <li>Single word: Simple LIKE match (e.g., "amazon" matches descriptions containing "amazon")
   *   <li>Multiple words: OR predicate (e.g., "amazon prime" matches descriptions containing
   *       "amazon" OR "prime")
   *   <li>Special characters (%, _) are escaped to prevent wildcard matching
   *   <li>Description filters match only the description field
   *   <li>Search-text filters match only the description field
   * </ul>
   *
   * <p>For date, timestamp, and numeric range fields, appropriate greater-than / less-than
   * comparisons are applied.
   *
   * @param criteria The transaction criteria with user-specified criteria
   * @return a {@link Specification} to be used with Spring Data repositories
   */
  public static Specification<Transaction> withCriteria(TransactionCriteria criteria) {
    return (root, query, cb) -> {
      var effectiveCriteria = criteria == null ? TransactionCriteria.empty() : criteria;
      List<Predicate> predicates = new ArrayList<>();

      // ID
      if (effectiveCriteria.id() != null) {
        predicates.add(cb.equal(root.get("id"), effectiveCriteria.id()));
      }

      // Owner ID (exact match)
      if (effectiveCriteria.ownerId() != null && !effectiveCriteria.ownerId().isBlank()) {
        predicates.add(cb.equal(root.get("ownerId"), effectiveCriteria.ownerId()));
      }

      // Account IDs (case-insensitive LIKE with multi-word OR support)
      Predicate accountIdPredicate =
          createAnyTextFilterPredicate(cb, root.get("accountId"), effectiveCriteria.accountIds());
      if (accountIdPredicate != null) {
        predicates.add(accountIdPredicate);
      }

      // Bank names (case-insensitive LIKE with multi-word OR support)
      Predicate bankNamePredicate =
          createAnyTextFilterPredicate(cb, root.get("bankName"), effectiveCriteria.bankNames());
      if (bankNamePredicate != null) {
        predicates.add(bankNamePredicate);
      }

      // Currency codes (case-insensitive exact match)
      Predicate currencyIsoCodePredicate =
          createAnyCaseInsensitiveExactPredicate(
              cb, root.get("currencyIsoCode"), effectiveCriteria.currencyIsoCodes());
      if (currencyIsoCodePredicate != null) {
        predicates.add(currencyIsoCodePredicate);
      }

      // Description (case-insensitive LIKE with multi-word OR support)
      Predicate descriptionPredicate =
          createTextFilterPredicate(cb, root.get("description"), effectiveCriteria.description());
      if (descriptionPredicate != null) {
        predicates.add(descriptionPredicate);
      }

      // Saved-view search text (case-insensitive LIKE against description)
      Predicate searchTextPredicate =
          createTextFilterPredicate(cb, root.get("description"), effectiveCriteria.searchText());
      if (searchTextPredicate != null) {
        predicates.add(searchTextPredicate);
      }

      // Transaction type (enum)
      if (effectiveCriteria.type() != null) {
        predicates.add(cb.equal(root.get("type"), effectiveCriteria.type()));
      }

      // ===== Date range (Transaction.date) =====
      if (effectiveCriteria.dateFrom() != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("date"), effectiveCriteria.dateFrom()));
      }
      if (effectiveCriteria.dateTo() != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("date"), effectiveCriteria.dateTo()));
      }

      // ===== Amount range =====
      if (effectiveCriteria.minAmount() != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), effectiveCriteria.minAmount()));
      }
      if (effectiveCriteria.maxAmount() != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("amount"), effectiveCriteria.maxAmount()));
      }

      // ===== CreatedAt range =====
      if (effectiveCriteria.createdAfter() != null) {
        predicates.add(
            cb.greaterThanOrEqualTo(root.get("createdAt"), effectiveCriteria.createdAfter()));
      }
      if (effectiveCriteria.createdBefore() != null) {
        predicates.add(
            cb.lessThanOrEqualTo(root.get("createdAt"), effectiveCriteria.createdBefore()));
      }

      // ===== UpdatedAt range =====
      if (effectiveCriteria.updatedAfter() != null) {
        predicates.add(
            cb.greaterThanOrEqualTo(root.get("updatedAt"), effectiveCriteria.updatedAfter()));
      }
      if (effectiveCriteria.updatedBefore() != null) {
        predicates.add(
            cb.lessThanOrEqualTo(root.get("updatedAt"), effectiveCriteria.updatedBefore()));
      }

      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }

  /**
   * Builds a JPA {@link Specification} from HTTP transaction search filters.
   *
   * <p>This compatibility overload delegates to {@link #withCriteria(TransactionCriteria)} so all
   * transaction query predicates are built from the shared internal model.
   *
   * @param filter The transaction filter with user-specified criteria
   * @return a {@link Specification} to be used with Spring Data repositories
   */
  public static Specification<Transaction> withFilter(TransactionFilter filter) {
    return withCriteria(TransactionCriteria.fromFilter(filter));
  }

  /**
   * Creates a specification that filters transactions by owner ID (exact match).
   *
   * @param ownerId the owner ID to filter by
   * @return a specification matching transactions owned by the given user
   */
  public static Specification<Transaction> byOwner(String ownerId) {
    Objects.requireNonNull(ownerId, "ownerId must not be null");
    return (root, query, cb) -> cb.equal(root.get("ownerId"), ownerId);
  }

  /**
   * Escapes special LIKE wildcard characters (%, _) in user input to prevent unintended pattern
   * matching.
   *
   * @param input The user input string
   * @return The escaped string safe for use in LIKE patterns
   */
  private static String escapeLikePattern(String input) {
    return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Predicate createAnyTextFilterPredicate(
      CriteriaBuilder cb, Expression<String> fieldPath, Set<String> filterValues) {
    if (filterValues == null || filterValues.isEmpty()) {
      return null;
    }

    List<Predicate> valuePredicates = new ArrayList<>();
    for (var filterValue : filterValues) {
      var valuePredicate = createTextFilterPredicate(cb, fieldPath, filterValue);
      if (valuePredicate != null) {
        valuePredicates.add(valuePredicate);
      }
    }

    return combineWithOr(cb, valuePredicates);
  }

  private static Predicate createAnyCaseInsensitiveExactPredicate(
      CriteriaBuilder cb, Expression<String> fieldPath, Set<String> filterValues) {
    if (filterValues == null || filterValues.isEmpty()) {
      return null;
    }

    var normalizedValues =
        filterValues.stream()
            .filter(Objects::nonNull)
            .filter(value -> !value.isBlank())
            .map(String::toLowerCase)
            .toList();
    if (normalizedValues.isEmpty()) {
      return null;
    }

    return cb.lower(fieldPath).in(normalizedValues);
  }

  private static Predicate combineWithOr(CriteriaBuilder cb, List<Predicate> predicates) {
    if (predicates == null || predicates.isEmpty()) {
      return null;
    }

    return predicates.size() == 1 ? predicates.get(0) : cb.or(predicates.toArray(new Predicate[0]));
  }

  /**
   * Creates a predicate for text field filtering with multi-word OR support.
   *
   * <p>If the input contains multiple whitespace-separated words, creates an OR predicate matching
   * any of the words. Single words create a simple LIKE predicate. All LIKE special characters are
   * escaped.
   *
   * @param cb The CriteriaBuilder
   * @param fieldPath The field path to filter on
   * @param filterValue The filter value (may contain multiple words)
   * @return A predicate for the text filter, or null if the filter value is null/blank
   */
  private static Predicate createTextFilterPredicate(
      CriteriaBuilder cb, Expression<String> fieldPath, String filterValue) {
    if (filterValue == null || filterValue.isBlank()) {
      return null;
    }

    String[] words = filterValue.trim().split("\\s+");
    List<Predicate> wordPredicates = new ArrayList<>();

    for (String word : words) {
      if (!word.isBlank()) {
        String escapedWord = escapeLikePattern(word.toLowerCase());
        wordPredicates.add(cb.like(cb.lower(fieldPath), "%" + escapedWord + "%", ESCAPE_CHAR));
      }
    }

    if (wordPredicates.isEmpty()) {
      return null;
    }

    return combineWithOr(cb, wordPredicates);
  }
}
