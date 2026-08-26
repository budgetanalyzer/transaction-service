package org.budgetanalyzer.transaction.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Static association between a saved view and a transaction. */
@Entity
@IdClass(SavedViewTransactionId.class)
@Table(name = "saved_view_transaction")
public class SavedViewTransaction {

  @Id
  @Column(name = "view_id", nullable = false)
  private UUID viewId;

  @Id
  @Column(name = "transaction_id", nullable = false)
  private Long transactionId;

  protected SavedViewTransaction() {}

  public SavedViewTransaction(UUID viewId, Long transactionId) {
    this.viewId = viewId;
    this.transactionId = transactionId;
  }

  public UUID getViewId() {
    return viewId;
  }

  public Long getTransactionId() {
    return transactionId;
  }
}
