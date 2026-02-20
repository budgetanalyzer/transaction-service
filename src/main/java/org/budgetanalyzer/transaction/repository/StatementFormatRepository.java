package org.budgetanalyzer.transaction.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.StatementFormat;

/** Repository for StatementFormat entities. */
public interface StatementFormatRepository extends JpaRepository<StatementFormat, Long> {

  /**
   * Finds a statement format by its format key.
   *
   * @param formatKey the unique format identifier
   * @return the statement format if found
   */
  Optional<StatementFormat> findByFormatKey(String formatKey);

  /**
   * Finds an enabled statement format by its format key.
   *
   * @param formatKey the unique format identifier
   * @return the statement format if found and enabled
   */
  Optional<StatementFormat> findByFormatKeyAndEnabledTrue(String formatKey);

  /**
   * Finds all enabled statement formats of a specific type.
   *
   * @param formatType the format type (CSV, PDF, XLSX)
   * @return list of enabled formats of the specified type
   */
  List<StatementFormat> findByFormatTypeAndEnabledTrue(FormatType formatType);

  /**
   * Finds all enabled statement formats.
   *
   * @return list of all enabled formats
   */
  List<StatementFormat> findByEnabledTrue();

  /**
   * Checks if a format key already exists.
   *
   * @param formatKey the format key to check
   * @return true if the format key exists
   */
  boolean existsByFormatKey(String formatKey);
}
