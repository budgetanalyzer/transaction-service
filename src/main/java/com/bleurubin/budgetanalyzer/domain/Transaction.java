package com.bleurubin.budgetanalyzer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** Transaction entity representing a financial transaction. */
@Entity
@Schema(description = "Transaction entity representing a financial transaction")
public class Transaction {

  /** Unique identifier for the transaction. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Schema(
      description = "Unique identifier for the transaction",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "1")
  private Long id;

  /** Identifier for the account associated with the transaction. */
  @Schema(
      description = "Identifier for the account associated with the transaction",
      requiredMode = Schema.RequiredMode.NOT_REQUIRED,
      example = "checking-3223")
  private String accountId;

  /** Name of the bank where the transaction occurred. */
  @NotNull
  @Schema(
      description = "Name of the bank where the transaction occurred",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "Capital One")
  private String bankName;

  /** Date of the transaction. */
  @NotNull
  @Schema(
      description = "Date of the transaction",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "2025-10-14")
  private LocalDate date;

  /** ISO currency code for the transaction. */
  @NotNull
  @Schema(
      description = "ISO currency code for the transaction",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "USD")
  private String currencyIsoCode;

  /** Amount of the transaction. */
  @NotNull
  @Schema(
      description = "Amount of the transaction",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "100.50")
  private BigDecimal amount;

  /** Type of the transaction (e.g., DEBIT, CREDIT). */
  @Enumerated(EnumType.STRING)
  @NotNull
  @Schema(
      description = "Type of the transaction",
      requiredMode = Schema.RequiredMode.REQUIRED,
      allowableValues = {"CREDIT", "DEBIT"},
      example = "DEBIT")
  private TransactionType type;

  /** Description of the transaction. */
  @NotNull
  @Schema(
      description = "Description of the transaction",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "Grocery shopping")
  private String description;

  /** Timestamp when the transaction was created. */
  @Column(updatable = false)
  @Schema(
      description = "Timestamp when the transaction was created",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "2025-10-14T12:34:56Z")
  private Instant createdAt;

  /** Timestamp when the transaction was last updated. */
  @Schema(
      description = "Timestamp when the transaction was last updated",
      requiredMode = Schema.RequiredMode.REQUIRED,
      example = "2025-10-14T12:34:56Z")
  private Instant updatedAt;

  /** Boolean indicating if the transaction has been soft deleted. */
  @Column(nullable = false)
  private Boolean deleted = false;

  /** Timestamp when the transaction was soft deleted. */
  private Instant deletedAt;

  /** Sets the creation and update timestamps before persisting. */
  @PrePersist
  public void onCreate() {
    createdAt = updatedAt = Instant.now();
  }

  /** Updates the update timestamp before updating. */
  @PreUpdate
  public void onUpdate() {
    updatedAt = Instant.now();
  }

  /**
   * Returns the unique identifier for the transaction.
   *
   * @return the transaction ID
   */
  public Long getId() {
    return id;
  }

  /**
   * Sets the unique identifier for the transaction.
   *
   * @param id the transaction ID
   */
  public void setId(Long id) {
    this.id = id;
  }

  /**
   * Returns the account ID associated with the transaction.
   *
   * @return the account ID
   */
  public String getAccountId() {
    return accountId;
  }

  /**
   * Sets the account ID associated with the transaction.
   *
   * @param accountId the account ID
   */
  public void setAccountId(String accountId) {
    this.accountId = accountId;
  }

  /**
   * Returns the bank name.
   *
   * @return the bank name
   */
  public String getBankName() {
    return bankName;
  }

  /**
   * Sets the bank name.
   *
   * @param bankName the bank name
   */
  public void setBankName(String bankName) {
    this.bankName = bankName;
  }

  /**
   * Returns the date of the transaction.
   *
   * @return the transaction date
   */
  public LocalDate getDate() {
    return date;
  }

  /**
   * Sets the date of the transaction.
   *
   * @param date the transaction date
   */
  public void setDate(LocalDate date) {
    this.date = date;
  }

  /**
   * Returns the ISO currency code.
   *
   * @return the currency ISO code
   */
  public String getCurrencyIsoCode() {
    return currencyIsoCode;
  }

  /**
   * Sets the ISO currency code.
   *
   * @param currencyIsoCode the currency ISO code
   */
  public void setCurrencyIsoCode(String currencyIsoCode) {
    this.currencyIsoCode = currencyIsoCode;
  }

  /**
   * Returns the amount of the transaction.
   *
   * @return the transaction amount
   */
  public BigDecimal getAmount() {
    return amount;
  }

  /**
   * Sets the amount of the transaction.
   *
   * @param amount the transaction amount
   */
  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  /**
   * Returns the type of the transaction.
   *
   * @return the transaction type
   */
  public TransactionType getType() {
    return type;
  }

  /**
   * Sets the type of the transaction.
   *
   * @param type the transaction type
   */
  public void setType(TransactionType type) {
    this.type = type;
  }

  /**
   * Returns the description of the transaction.
   *
   * @return the transaction description
   */
  public String getDescription() {
    return description;
  }

  /**
   * Sets the description of the transaction.
   *
   * @param description the transaction description
   */
  public void setDescription(String description) {
    this.description = description;
  }

  /**
   * Returns the creation timestamp.
   *
   * @return the creation timestamp
   */
  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * Sets the creation timestamp.
   *
   * @param createdAt the creation timestamp
   */
  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  /**
   * Returns the last updated timestamp.
   *
   * @return the last updated timestamp
   */
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /**
   * Sets the last updated timestamp.
   *
   * @param updatedAt the last updated timestamp
   */
  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  /**
   * Returns whether or not the transaction was soft deleted.
   *
   * @return true if transaction was soft deleted
   */
  public Boolean getDeleted() {
    return deleted;
  }

  /**
   * Sets the soft deleted flag.
   *
   * @param deleted the soft deleted flag
   */
  public void setDeleted(Boolean deleted) {
    this.deleted = deleted;
  }

  /**
   * Returns the soft deleted timestamp.
   *
   * @return the soft deleted timestamp
   */
  public Instant getDeletedAt() {
    return deletedAt;
  }

  /**
   * Sets the soft deleted timestamp.
   *
   * @param deletedAt the soft deleted timestamp
   */
  public void setDeletedAt(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }
}
