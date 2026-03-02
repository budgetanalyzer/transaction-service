package org.budgetanalyzer.transaction.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.budgetanalyzer.transaction.domain.SavedView;

/** Repository for {@link SavedView} entities. */
@Repository
public interface SavedViewRepository extends JpaRepository<SavedView, UUID> {

  /** Find all saved views for a user, ordered by creation date descending. */
  List<SavedView> findByUserIdOrderByCreatedAtDesc(String userId);

  /** Find a saved view by ID and user ID (for authorization). */
  Optional<SavedView> findByIdAndUserId(UUID id, String userId);
}
