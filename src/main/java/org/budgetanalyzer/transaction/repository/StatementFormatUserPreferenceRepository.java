package org.budgetanalyzer.transaction.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import org.budgetanalyzer.transaction.domain.StatementFormatUserPreference;

/** Repository for per-user statement format visibility preferences. */
public interface StatementFormatUserPreferenceRepository
    extends JpaRepository<StatementFormatUserPreference, Long> {

  /**
   * Finds a user's preference for a statement format.
   *
   * @param statementFormatId statement format ID
   * @param userId user ID
   * @return matching preference, if one exists
   */
  Optional<StatementFormatUserPreference> findByStatementFormatIdAndUserId(
      Long statementFormatId, String userId);
}
