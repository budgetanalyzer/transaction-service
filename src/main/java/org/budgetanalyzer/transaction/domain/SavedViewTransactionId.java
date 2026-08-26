package org.budgetanalyzer.transaction.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite identifier for one static saved-view membership. */
public class SavedViewTransactionId implements Serializable {

  private UUID viewId;
  private Long transactionId;

  public SavedViewTransactionId() {}

  public SavedViewTransactionId(UUID viewId, Long transactionId) {
    this.viewId = viewId;
    this.transactionId = transactionId;
  }

  public UUID getViewId() {
    return viewId;
  }

  public Long getTransactionId() {
    return transactionId;
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof SavedViewTransactionId that)) {
      return false;
    }
    return Objects.equals(viewId, that.viewId) && Objects.equals(transactionId, that.transactionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(viewId, transactionId);
  }
}
