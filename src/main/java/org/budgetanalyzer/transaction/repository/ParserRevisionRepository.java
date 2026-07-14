package org.budgetanalyzer.transaction.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import org.budgetanalyzer.transaction.domain.ParserRevision;

/** Repository for hidden statement parser revisions. */
public interface ParserRevisionRepository extends JpaRepository<ParserRevision, Long> {

  /**
   * Finds enabled revisions for a statement format in deterministic selection order.
   *
   * @param statementFormatId the parent statement format ID
   * @return enabled parser revisions ordered by priority and revision
   */
  @EntityGraph(attributePaths = "statementFormat")
  List<ParserRevision> findByStatementFormatIdAndEnabledTrueOrderByPriorityDescRevisionNumberDesc(
      Long statementFormatId);
}
