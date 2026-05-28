package org.budgetanalyzer.transaction.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

/** Hidden deterministic parser configuration for a statement format. */
@Entity
@Table(name = "parser_revision")
public class ParserRevision {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "statement_format_id", nullable = false)
  private StatementFormat statementFormat;

  @NotNull
  @Column(name = "revision_number", nullable = false)
  private Integer revisionNumber;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "parser_type", nullable = false, length = 30)
  private ParserType parserType;

  @Column(name = "handler_key", length = 100)
  private String handlerKey;

  @NotNull
  @Column(name = "config_schema_version", nullable = false)
  private Integer configSchemaVersion;

  @Column(name = "parser_config", columnDefinition = "text")
  private String parserConfig;

  @NotNull
  @Column(name = "priority", nullable = false)
  private Integer priority;

  @NotNull
  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  @Column(name = "promoted_from_parser_revision_id")
  private Long promotedFromParserRevisionId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Default constructor for JPA. */
  protected ParserRevision() {}

  /**
   * Creates a static-handler parser revision.
   *
   * @param statementFormat parent statement format
   * @param revisionNumber revision number under the parent format
   * @param handlerKey internal static extractor key
   * @return new parser revision
   */
  public static ParserRevision createStaticHandler(
      StatementFormat statementFormat, Integer revisionNumber, String handlerKey) {
    var parserRevision = new ParserRevision();
    parserRevision.statementFormat = statementFormat;
    parserRevision.revisionNumber = revisionNumber;
    parserRevision.parserType = ParserType.STATIC_HANDLER;
    parserRevision.handlerKey = handlerKey;
    parserRevision.configSchemaVersion = 1;
    parserRevision.priority = 0;
    parserRevision.enabled = true;
    return parserRevision;
  }

  /**
   * Creates a CSV column-configuration parser revision.
   *
   * @param statementFormat parent statement format
   * @param revisionNumber revision number under the parent format
   * @param parserConfig serialized deterministic parser configuration
   * @return new parser revision
   */
  public static ParserRevision createCsvColumnConfig(
      StatementFormat statementFormat, Integer revisionNumber, String parserConfig) {
    var parserRevision = new ParserRevision();
    parserRevision.statementFormat = statementFormat;
    parserRevision.revisionNumber = revisionNumber;
    parserRevision.parserType = ParserType.CSV_COLUMN_CONFIG;
    parserRevision.configSchemaVersion = 1;
    parserRevision.parserConfig = parserConfig;
    parserRevision.priority = 0;
    parserRevision.enabled = true;
    return parserRevision;
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

  public Integer getRevisionNumber() {
    return revisionNumber;
  }

  public ParserType getParserType() {
    return parserType;
  }

  public String getHandlerKey() {
    return handlerKey;
  }

  public Integer getConfigSchemaVersion() {
    return configSchemaVersion;
  }

  public String getParserConfig() {
    return parserConfig;
  }

  public Integer getPriority() {
    return priority;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Long getPromotedFromParserRevisionId() {
    return promotedFromParserRevisionId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
