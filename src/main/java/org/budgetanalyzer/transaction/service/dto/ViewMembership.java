package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/**
 * Service-layer DTO representing transaction membership in a saved view.
 *
 * <p>Used by SavedViewService, transformed to ViewMembershipResponse by controller. Contains only
 * active (non-deleted) transaction IDs grouped by membership type:
 *
 * <ul>
 *   <li><b>matched</b>: Transactions matching view criteria (excluding excluded IDs)
 *   <li><b>pinned</b>: Transactions explicitly pinned (excluding those already matched)
 *   <li><b>excluded</b>: Transactions explicitly excluded from matched set
 * </ul>
 *
 * <p>Soft-deleted transactions are automatically filtered out by repository methods.
 *
 * @param matched IDs of active transactions matching view criteria (excluding excluded IDs)
 * @param pinned IDs of active transactions explicitly pinned (excluding matched IDs)
 * @param excluded IDs of active transactions explicitly excluded
 */
public record ViewMembership(List<Long> matched, List<Long> pinned, List<Long> excluded) {}
