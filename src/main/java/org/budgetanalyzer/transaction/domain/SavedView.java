package org.budgetanalyzer.transaction.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.budgetanalyzer.transaction.domain.converter.LongSetConverter;
import org.budgetanalyzer.transaction.domain.converter.ViewCriteriaConverter;

/** Saved view (smart collection) for filtering and grouping transactions. */
@Entity
@Table(name = "saved_view")
public class SavedView {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "user_id", length = 50, nullable = false)
  private String userId;

  @Column(nullable = false)
  private String name;

  @Column(columnDefinition = "text", nullable = false)
  @Convert(converter = ViewCriteriaConverter.class)
  private ViewCriteria criteria;

  @Column(name = "open_ended", nullable = false)
  private boolean openEnded;

  @Column(name = "pinned_ids", columnDefinition = "text", nullable = false)
  @Convert(converter = LongSetConverter.class)
  private Set<Long> pinnedIds = new HashSet<>();

  @Column(name = "excluded_ids", columnDefinition = "text", nullable = false)
  @Convert(converter = LongSetConverter.class)
  private Set<Long> excludedIds = new HashSet<>();

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

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

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public ViewCriteria getCriteria() {
    return criteria;
  }

  public void setCriteria(ViewCriteria criteria) {
    this.criteria = criteria;
  }

  public boolean isOpenEnded() {
    return openEnded;
  }

  public void setOpenEnded(boolean openEnded) {
    this.openEnded = openEnded;
  }

  public Set<Long> getPinnedIds() {
    return pinnedIds;
  }

  public void setPinnedIds(Set<Long> pinnedIds) {
    this.pinnedIds = pinnedIds != null ? pinnedIds : new HashSet<>();
  }

  public Set<Long> getExcludedIds() {
    return excludedIds;
  }

  public void setExcludedIds(Set<Long> excludedIds) {
    this.excludedIds = excludedIds != null ? excludedIds : new HashSet<>();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Pins a transaction to this view. */
  public void pinTransaction(Long transactionId) {
    pinnedIds.add(transactionId);
    excludedIds.remove(transactionId);
  }

  /** Removes a pin from this view. */
  public void unpinTransaction(Long transactionId) {
    pinnedIds.remove(transactionId);
  }

  /** Excludes a transaction from this view. */
  public void excludeTransaction(Long transactionId) {
    excludedIds.add(transactionId);
    pinnedIds.remove(transactionId);
  }

  /** Removes an exclusion from this view. */
  public void unexcludeTransaction(Long transactionId) {
    excludedIds.remove(transactionId);
  }
}
