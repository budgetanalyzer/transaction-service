package org.budgetanalyzer.transaction.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC batch implementation for static saved-view membership insertion. */
public class SavedViewTransactionBatchRepositoryImpl
    implements SavedViewTransactionBatchRepository {

  private static final String INSERT_SQL =
      "INSERT INTO saved_view_transaction (view_id, transaction_id) VALUES (?, ?) "
          + "ON CONFLICT (view_id, transaction_id) DO NOTHING";

  private final JdbcTemplate jdbcTemplate;

  public SavedViewTransactionBatchRepositoryImpl(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int insertAll(UUID viewId, Collection<Long> transactionIds) {
    var orderedTransactionIds = transactionIds.stream().sorted().toList();
    if (orderedTransactionIds.isEmpty()) {
      return 0;
    }

    var updateCounts =
        jdbcTemplate.batchUpdate(
            INSERT_SQL,
            new BatchPreparedStatementSetter() {
              @Override
              public void setValues(PreparedStatement preparedStatement, int index)
                  throws SQLException {
                preparedStatement.setObject(1, viewId);
                preparedStatement.setLong(2, orderedTransactionIds.get(index));
              }

              @Override
              public int getBatchSize() {
                return orderedTransactionIds.size();
              }
            });

    return Arrays.stream(updateCounts).filter(updateCount -> updateCount > 0).sum();
  }
}
