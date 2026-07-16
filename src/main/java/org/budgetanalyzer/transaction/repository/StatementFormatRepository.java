package org.budgetanalyzer.transaction.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.budgetanalyzer.transaction.domain.StatementFormat;

/** Repository for StatementFormat entities. */
public interface StatementFormatRepository extends JpaRepository<StatementFormat, Long> {

  /** Finds all statement formats visible to a user. */
  @Query(
      """
      select statementFormat
      from StatementFormat statementFormat
      where statementFormat.scope = org.budgetanalyzer.transaction.domain.StatementFormatScope.SYSTEM
         or statementFormat.ownerId = :ownerId
      order by statementFormat.displayName asc, statementFormat.id asc
      """)
  List<StatementFormat> findVisibleToUser(@Param("ownerId") String ownerId);

  /** Finds an enabled statement format visible to a user by ID. */
  @Query(
      """
      select statementFormat
      from StatementFormat statementFormat
      where statementFormat.id = :id
        and statementFormat.enabled = true
        and (
          statementFormat.scope = org.budgetanalyzer.transaction.domain.StatementFormatScope.SYSTEM
          or statementFormat.ownerId = :ownerId
        )
      """)
  Optional<StatementFormat> findEnabledVisibleToUser(
      @Param("id") Long id, @Param("ownerId") String ownerId);

  /** Finds a statement format visible to a user by ID. */
  @Query(
      """
      select statementFormat
      from StatementFormat statementFormat
      where statementFormat.id = :id
        and (
          statementFormat.scope = org.budgetanalyzer.transaction.domain.StatementFormatScope.SYSTEM
          or statementFormat.ownerId = :ownerId
        )
      """)
  Optional<StatementFormat> findVisibleToUserById(
      @Param("id") Long id, @Param("ownerId") String ownerId);
}
