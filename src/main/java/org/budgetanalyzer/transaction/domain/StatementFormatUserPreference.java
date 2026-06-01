package org.budgetanalyzer.transaction.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

/** Per-user visibility preference for a statement format. */
@Entity
@Table(
    name = "statement_format_user_preference",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_statement_format_user_preference",
            columnNames = {"statement_format_id", "user_id"}))
public class StatementFormatUserPreference {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "statement_format_id", nullable = false)
  private StatementFormat statementFormat;

  @NotNull
  @Column(name = "user_id", nullable = false, length = 50)
  private String userId;

  @NotNull
  @Column(name = "hidden", nullable = false)
  private boolean hidden;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Default constructor for JPA. */
  protected StatementFormatUserPreference() {}

  /**
   * Creates a hidden preference row for a statement format and user.
   *
   * @param statementFormat statement format to hide
   * @param userId user ID that owns the preference
   * @return new hidden preference
   */
  public static StatementFormatUserPreference createHidden(
      StatementFormat statementFormat, String userId) {
    var statementFormatUserPreference = new StatementFormatUserPreference();
    statementFormatUserPreference.statementFormat = statementFormat;
    statementFormatUserPreference.userId = userId;
    statementFormatUserPreference.hidden = true;
    return statementFormatUserPreference;
  }

  @PrePersist
  protected void onCreate() {
    var now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public StatementFormat getStatementFormat() {
    return statementFormat;
  }

  public String getUserId() {
    return userId;
  }

  public boolean isHidden() {
    return hidden;
  }

  public void setHidden(boolean hidden) {
    this.hidden = hidden;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
