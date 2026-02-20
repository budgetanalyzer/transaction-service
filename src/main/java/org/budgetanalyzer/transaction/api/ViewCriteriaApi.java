package org.budgetanalyzer.transaction.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.domain.ViewCriteria;

/** Filter criteria for a saved view. */
@Schema(description = "Filter criteria for a saved view")
public record ViewCriteriaApi(
    @Schema(description = "Start date for transaction date range", example = "2024-12-01")
        LocalDate startDate,
    @Schema(description = "End date for transaction date range (null for open-ended views)")
        LocalDate endDate,
    @Schema(description = "Account IDs to filter by") Set<String> accountIds,
    @Schema(description = "Bank names to filter by", example = "[\"Capital One\", \"Truist\"]")
        Set<String> bankNames,
    @Schema(description = "Currency ISO codes to filter by", example = "[\"USD\", \"EUR\"]")
        Set<String> currencyIsoCodes,
    @Schema(description = "Minimum transaction amount", example = "10.00") BigDecimal minAmount,
    @Schema(description = "Maximum transaction amount", example = "500.00") BigDecimal maxAmount,
    @Schema(description = "Text to match in the transaction description", example = "coffee")
        String searchText) {

  /** Converts this API criteria to a domain ViewCriteria. */
  public ViewCriteria toDomain() {
    return new ViewCriteria(
        startDate,
        endDate,
        accountIds,
        bankNames,
        currencyIsoCodes,
        minAmount,
        maxAmount,
        searchText);
  }

  /** Creates an API ViewCriteriaApi from a domain ViewCriteria. */
  public static ViewCriteriaApi from(ViewCriteria criteria) {
    if (criteria == null) {
      return new ViewCriteriaApi(null, null, null, null, null, null, null, null);
    }
    return new ViewCriteriaApi(
        criteria.startDate(),
        criteria.endDate(),
        criteria.accountIds(),
        criteria.bankNames(),
        criteria.currencyIsoCodes(),
        criteria.minAmount(),
        criteria.maxAmount(),
        criteria.searchText());
  }
}
