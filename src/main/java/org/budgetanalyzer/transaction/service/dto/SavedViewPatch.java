package org.budgetanalyzer.transaction.service.dto;

import org.budgetanalyzer.transaction.domain.ViewCriteria;

/**
 * Service-layer patch for updating a saved view.
 *
 * <p>Null fields are skipped; Boolean (not boolean) allows distinguishing "unset" from "false".
 */
public record SavedViewPatch(String name, ViewCriteria criteria, Boolean openEnded) {}
