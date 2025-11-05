package com.bleurubin.budgetanalyzer.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;

import com.bleurubin.core.domain.SoftDeletableEntity;

/** Transaction entity representing a financial transaction. */
@Entity
public class Transaction extends SoftDeletableEntity {

  /** Unique identifier for the transaction. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Identifier for the account associated with the transaction. */
  private String accountId;

  /** Name of the bank where the transaction occurred. */
  @NotNull private String bankName;

  /** Date of the transaction. */
  @NotNull private LocalDate date;

  /** ISO currency code for the transaction. */
  @NotNull private String currencyIsoCode;

  /** Amount of the transaction. */
  @NotNull private BigDecimal amount;

  /** Type of the transaction (e.g., DEBIT, CREDIT). */
  @Enumerated(EnumType.STRING)
  @NotNull
  private TransactionType type;

  /** Description of the transaction. */
  @NotNull private String description;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getAccountId() {
    return accountId;
  }

  public void setAccountId(String accountId) {
    this.accountId = accountId;
  }

  public String getBankName() {
    return bankName;
  }

  public void setBankName(String bankName) {
    this.bankName = bankName;
  }

  public LocalDate getDate() {
    return date;
  }

  public void setDate(LocalDate date) {
    this.date = date;
  }

  public String getCurrencyIsoCode() {
    return currencyIsoCode;
  }

  public void setCurrencyIsoCode(String currencyIsoCode) {
    this.currencyIsoCode = currencyIsoCode;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public TransactionType getType() {
    return type;
  }

  public void setType(TransactionType type) {
    this.type = type;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }
}
