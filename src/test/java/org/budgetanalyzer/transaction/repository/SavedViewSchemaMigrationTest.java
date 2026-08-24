package org.budgetanalyzer.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SavedViewSchemaMigrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("saved_view_migration")
          .withUsername("test")
          .withPassword("test");

  @Test
  void v22DeletesLegacyRowsAndCreatesExactStaticMembershipSchema() throws Exception {
    var flywayThroughV21 = flyway(MigrationVersion.fromVersion("21"));
    flywayThroughV21.clean();
    flywayThroughV21.migrate();
    var viewId = UUID.randomUUID();
    insertLegacyView(viewId);

    flyway(MigrationVersion.LATEST).migrate();

    try (var connection =
        DriverManager.getConnection(
            POSTGRESQL_CONTAINER.getJdbcUrl(),
            POSTGRESQL_CONTAINER.getUsername(),
            POSTGRESQL_CONTAINER.getPassword())) {
      assertThat(queryLong(connection, "SELECT COUNT(*) FROM saved_view")).isZero();
      assertThat(tableColumns(connection, "saved_view"))
          .containsExactlyInAnyOrder("id", "user_id", "name", "created_at", "updated_at");
      assertThat(tableColumns(connection, "saved_view_transaction"))
          .containsExactlyInAnyOrder("view_id", "transaction_id");
      assertThat(
              queryLong(
                  connection,
                  "SELECT COUNT(*) FROM pg_indexes "
                      + "WHERE tablename = 'saved_view_transaction' "
                      + "AND indexdef LIKE '%(transaction_id)%'"))
          .isEqualTo(1);

      var transactionId = insertTransaction(connection);
      insertStaticViewAndMembership(connection, viewId, transactionId);
      assertThatThrownBy(
              () ->
                  connection
                      .createStatement()
                      .executeUpdate("DELETE FROM transaction WHERE id = " + transactionId))
          .isInstanceOf(java.sql.SQLException.class);
      connection.rollback();
      connection.setAutoCommit(true);

      connection
          .createStatement()
          .executeUpdate("DELETE FROM saved_view WHERE id = '" + viewId + "'");
      assertThat(queryLong(connection, "SELECT COUNT(*) FROM saved_view_transaction")).isZero();
    }
  }

  private Flyway flyway(MigrationVersion target) {
    return Flyway.configure()
        .dataSource(
            POSTGRESQL_CONTAINER.getJdbcUrl(),
            POSTGRESQL_CONTAINER.getUsername(),
            POSTGRESQL_CONTAINER.getPassword())
        .cleanDisabled(false)
        .target(target)
        .load();
  }

  private void insertLegacyView(UUID viewId) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRESQL_CONTAINER.getJdbcUrl(),
                POSTGRESQL_CONTAINER.getUsername(),
                POSTGRESQL_CONTAINER.getPassword());
        var preparedStatement =
            connection.prepareStatement(
                "INSERT INTO saved_view "
                    + "(id, user_id, name, criteria, open_ended, pinned_ids, excluded_ids) "
                    + "VALUES (?, 'owner', 'Legacy', '{}', false, '[]', '[]')")) {
      preparedStatement.setObject(1, viewId);
      preparedStatement.executeUpdate();
    }
  }

  private long insertTransaction(java.sql.Connection connection) throws Exception {
    try (var preparedStatement =
        connection.prepareStatement(
            "INSERT INTO transaction "
                + "(bank_name, date, currency_iso_code, amount, type, description, created_at, "
                + "deleted, owner_id) VALUES "
                + "('Test Bank', DATE '2024-01-01', 'USD', 1.00, 'DEBIT', 'Test', ?, false, "
                + "'owner') RETURNING id")) {
      preparedStatement.setTimestamp(1, Timestamp.from(Instant.parse("2024-01-01T00:00:00Z")));
      try (var resultSet = preparedStatement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  private void insertStaticViewAndMembership(
      java.sql.Connection connection, UUID viewId, long transactionId) throws Exception {
    connection
        .createStatement()
        .executeUpdate(
            "INSERT INTO saved_view (id, user_id, name) VALUES ('"
                + viewId
                + "', 'owner', 'Static')");
    connection
        .createStatement()
        .executeUpdate(
            "INSERT INTO saved_view_transaction (view_id, transaction_id) VALUES ('"
                + viewId
                + "', "
                + transactionId
                + ")");
    connection.setAutoCommit(false);
  }

  private ArrayList<String> tableColumns(java.sql.Connection connection, String tableName)
      throws Exception {
    var columns = new ArrayList<String>();
    try (var preparedStatement =
        connection.prepareStatement(
            "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position")) {
      preparedStatement.setString(1, tableName);
      try (var resultSet = preparedStatement.executeQuery()) {
        while (resultSet.next()) {
          columns.add(resultSet.getString(1));
        }
      }
    }
    return columns;
  }

  private long queryLong(java.sql.Connection connection, String sql) throws Exception {
    try (var statement = connection.createStatement();
        var resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }
}
