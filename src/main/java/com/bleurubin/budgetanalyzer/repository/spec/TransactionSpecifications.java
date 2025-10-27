package com.bleurubin.budgetanalyzer.repository.spec;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.jpa.domain.Specification;

import com.bleurubin.budgetanalyzer.api.request.TransactionFilter;
import com.bleurubin.budgetanalyzer.domain.Transaction;

/** Specification builder for {@link Transaction} queries based on {@link TransactionFilter}. */
public class TransactionSpecifications {

  /**
   * Builds a JPA {@link Specification} for filtering {@link Transaction} entities using the
   * provided {@link TransactionFilter}.
   *
   * <p>All non-null fields in the filter are converted into predicates. For text fields, {@code
   * LIKE} operations (case-insensitive) are used. For date, timestamp, and numeric range fields,
   * appropriate greater-than / less-than comparisons are applied.
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

      // Account ID (case-insensitive LIKE)
      if (filter.accountId() != null && !filter.accountId().isBlank()) {
        predicates.add(
            cb.like(cb.lower(root.get("accountId")), "%" + filter.accountId().toLowerCase() + "%"));
      }

      // Bank name (case-insensitive LIKE)
      if (filter.bankName() != null && !filter.bankName().isBlank()) {
        predicates.add(
            cb.like(cb.lower(root.get("bankName")), "%" + filter.bankName().toLowerCase() + "%"));
      }

      // Currency code (case-insensitive exact match)
      if (filter.currencyIsoCode() != null && !filter.currencyIsoCode().isBlank()) {
        predicates.add(
            cb.equal(
                cb.lower(root.get("currencyIsoCode")), filter.currencyIsoCode().toLowerCase()));
      }

      // Description (case-insensitive LIKE)
      if (filter.description() != null && !filter.description().isBlank()) {
        predicates.add(
            cb.like(
                cb.lower(root.get("description")), "%" + filter.description().toLowerCase() + "%"));
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
}
