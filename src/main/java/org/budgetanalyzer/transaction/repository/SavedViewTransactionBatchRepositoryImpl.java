package org.budgetanalyzer.transaction.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/** Existence-filtered JDBC batch implementation for static saved-view membership insertion. */
public class SavedViewTransactionBatchRepositoryImpl
    implements SavedViewTransactionBatchRepository {

  private static final String INSERT_SQL =
      "INSERT INTO saved_view_transaction (view_id, transaction_id) VALUES (?, ?)";
  private static final String FIND_EXISTING_MEMBERSHIPS_QUERY =
      "SELECT membership.transactionId FROM SavedViewTransaction membership "
          + "WHERE membership.viewId = :viewId "
          + "AND membership.transactionId IN :transactionIds";

  private final EntityManager entityManager;
  private final JdbcTemplate jdbcTemplate;

  public SavedViewTransactionBatchRepositoryImpl(
      EntityManager entityManager, JdbcTemplate jdbcTemplate) {
    this.entityManager = entityManager;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int insertMissing(UUID viewId, Collection<Long> transactionIds) {
    var orderedTransactionIds = transactionIds.stream().distinct().sorted().toList();
    if (orderedTransactionIds.isEmpty()) {
      return 0;
    }

    var existingTransactionIds =
        new HashSet<>(
            entityManager
                .createQuery(FIND_EXISTING_MEMBERSHIPS_QUERY, Long.class)
                .setParameter("viewId", viewId)
                .setParameter("transactionIds", orderedTransactionIds)
                .getResultList());
    var missingTransactionIds =
        orderedTransactionIds.stream()
            .filter(transactionId -> !existingTransactionIds.contains(transactionId))
            .toList();
    if (missingTransactionIds.isEmpty()) {
      return 0;
    }

    jdbcTemplate.batchUpdate(
        INSERT_SQL,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement preparedStatement, int index)
              throws SQLException {
            preparedStatement.setObject(1, viewId);
            preparedStatement.setLong(2, missingTransactionIds.get(index));
          }

          @Override
          public int getBatchSize() {
            return missingTransactionIds.size();
          }
        });

    return missingTransactionIds.size();
  }
}
