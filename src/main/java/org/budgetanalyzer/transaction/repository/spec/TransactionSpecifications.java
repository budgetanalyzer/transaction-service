package org.budgetanalyzer.transaction.repository.spec;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.jpa.domain.Specification;

import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.domain.Transaction;

/** Specification builder for {@link Transaction} queries based on {@link TransactionFilter}. */
public class TransactionSpecifications {

  private static final char ESCAPE_CHAR = '\\';

  /**
   * Builds a JPA {@link Specification} for filtering {@link Transaction} entities using the
   * provided {@link TransactionFilter}.
   *
   * <p>All non-null fields in the filter are converted into predicates. For text fields, {@code
   * LIKE} operations (case-insensitive) are used with the following behavior:
   *
   * <ul>
   *   <li>Single word: Simple LIKE match (e.g., "amazon" matches descriptions containing "amazon")
   *   <li>Multiple words: OR predicate (e.g., "amazon prime" matches descriptions containing
   *       "amazon" OR "prime")
   *   <li>Special characters (%, _) are escaped to prevent wildcard matching
   * </ul>
   *
   * <p>For date, timestamp, and numeric range fields, appropriate greater-than / less-than
   * comparisons are applied.
   *
   * @param filter The transaction filter with user-specified criteria
   * @return a {@link Specification} to be used with Spring Data repositories
   */
  public static Specification<Transaction> withFilter(TransactionFilter filter) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();

      // ID
      if (filter.id() != null) {
        predicates.add(cb.equal(root.get("id"), filter.id()));
      }

      // Account ID (case-insensitive LIKE with multi-word OR support)
      Predicate accountIdPredicate =
          createTextFilterPredicate(cb, root.get("accountId"), filter.accountId());
      if (accountIdPredicate != null) {
        predicates.add(accountIdPredicate);
      }

      // Bank name (case-insensitive LIKE with multi-word OR support)
      Predicate bankNamePredicate =
          createTextFilterPredicate(cb, root.get("bankName"), filter.bankName());
      if (bankNamePredicate != null) {
        predicates.add(bankNamePredicate);
      }

      // Currency code (case-insensitive exact match)
      if (filter.currencyIsoCode() != null && !filter.currencyIsoCode().isBlank()) {
        predicates.add(
            cb.equal(
                cb.lower(root.get("currencyIsoCode")), filter.currencyIsoCode().toLowerCase()));
      }

      // Description (case-insensitive LIKE with multi-word OR support)
      Predicate descriptionPredicate =
          createTextFilterPredicate(cb, root.get("description"), filter.description());
      if (descriptionPredicate != null) {
        predicates.add(descriptionPredicate);
      }

      // Transaction type (enum)
      if (filter.type() != null) {
        predicates.add(cb.equal(root.get("type"), filter.type()));
      }

      // ===== Date range (Transaction.date) =====
      if (filter.dateFrom() != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("date"), filter.dateFrom()));
      }
      if (filter.dateTo() != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("date"), filter.dateTo()));
      }

      // ===== Amount range =====
      if (filter.minAmount() != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), filter.minAmount()));
      }
      if (filter.maxAmount() != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("amount"), filter.maxAmount()));
      }

      // ===== CreatedAt range =====
      if (filter.createdAfter() != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.createdAfter()));
      }
      if (filter.createdBefore() != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filter.createdBefore()));
      }

      // ===== UpdatedAt range =====
      if (filter.updatedAfter() != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("updatedAt"), filter.updatedAfter()));
      }
      if (filter.updatedBefore() != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("updatedAt"), filter.updatedBefore()));
      }

      return cb.and(predicates.toArray(new Predicate[0]));
    };
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
      jakarta.persistence.criteria.CriteriaBuilder cb,
      jakarta.persistence.criteria.Expression<String> fieldPath,
      String filterValue) {
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

    return wordPredicates.size() == 1
        ? wordPredicates.get(0)
        : cb.or(wordPredicates.toArray(new Predicate[0]));
  }
}
