package org.budgetanalyzer.transaction.service.dto;

import org.budgetanalyzer.transaction.domain.StatementFormat;

/**
 * Statement format plus current-user visibility preference for list responses.
 *
 * @param statementFormat statement format visible to the caller
 * @param hidden whether the current user has hidden this format from normal selection
 */
public record StatementFormatListItem(StatementFormat statementFormat, boolean hidden) {}
