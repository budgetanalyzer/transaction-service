package org.budgetanalyzer.transaction.api;

import java.util.List;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.budgetanalyzer.service.api.PagedResponse;
import org.budgetanalyzer.service.exception.InvalidRequestException;
import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.api.response.AdminTransactionResponse;
import org.budgetanalyzer.transaction.service.TransactionService;

/** Admin controller for searching transactions across all users. */
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Transactions", description = "Admin transaction search and management")
@RestController
@RequestMapping(path = "/v1/admin/transactions")
public class AdminTransactionController {

  private static final Logger log = LoggerFactory.getLogger(AdminTransactionController.class);
  private static final List<String> ALLOWED_SORT_FIELDS =
      List.of(
          "id",
          "ownerId",
          "accountId",
          "bankName",
          "date",
          "currencyIsoCode",
          "amount",
          "type",
          "description",
          "createdAt",
          "updatedAt");

  private final TransactionService transactionService;

  /**
   * Constructs a new AdminTransactionController.
   *
   * @param transactionService the transaction service
   */
  public AdminTransactionController(TransactionService transactionService) {
    this.transactionService = transactionService;
  }

  /**
   * Searches transactions across all users with pagination and filtering.
   *
   * @param filter the search filter criteria
   * @param pageable pagination and sorting parameters
   * @return a paged response of admin transaction results
   */
  @Operation(summary = "Search transactions across all users")
  @GetMapping(produces = "application/json")
  public PagedResponse<AdminTransactionResponse> searchTransactions(
      @Valid TransactionFilter filter,
      @PageableDefault(
              size = 50,
              sort = {"date", "id"},
              direction = Sort.Direction.DESC)
          Pageable pageable) {
    validateSortFields(pageable);
    log.info(
        "Admin transaction search request - page: {} size: {} sort: {} "
            + "hasIdentityFilters: {} hasTextFilters: {} hasDateFilter: {} "
            + "hasAmountFilter: {} hasTimestampFilter: {}",
        pageable.getPageNumber(),
        pageable.getPageSize(),
        pageable.getSort(),
        hasIdentityFilters(filter),
        hasTextFilters(filter),
        hasDateFilter(filter),
        hasAmountFilter(filter),
        hasTimestampFilter(filter));
    var page = transactionService.search(filter, pageable);

    return PagedResponse.from(page, AdminTransactionResponse::from);
  }

  /**
   * Counts transactions across all users matching the provided filter criteria.
   *
   * @param filter the search filter criteria
   * @return the count of matching transactions
   */
  @Operation(summary = "Count transactions across all users")
  @GetMapping(path = "/count", produces = "application/json")
  public long countTransactions(@Valid TransactionFilter filter) {
    log.info(
        "Admin transaction count request - hasIdentityFilters: {} hasTextFilters: {} "
            + "hasDateFilter: {} hasAmountFilter: {} hasTimestampFilter: {}",
        hasIdentityFilters(filter),
        hasTextFilters(filter),
        hasDateFilter(filter),
        hasAmountFilter(filter),
        hasTimestampFilter(filter));
    return transactionService.countActive(filter);
  }

  private void validateSortFields(Pageable pageable) {
    for (var sortOrder : pageable.getSort()) {
      if (!ALLOWED_SORT_FIELDS.contains(sortOrder.getProperty())) {
        throw new InvalidRequestException(
            "Unsupported sort field: "
                + sortOrder.getProperty()
                + ". Allowed sort fields: "
                + String.join(", ", ALLOWED_SORT_FIELDS));
      }
    }
  }

  private boolean hasIdentityFilters(TransactionFilter filter) {
    return filter.id() != null || hasText(filter.ownerId());
  }

  private boolean hasTextFilters(TransactionFilter filter) {
    return hasText(filter.accountId())
        || hasText(filter.bankName())
        || hasText(filter.currencyIsoCode())
        || hasText(filter.description())
        || filter.type() != null;
  }

  private boolean hasDateFilter(TransactionFilter filter) {
    return filter.dateFrom() != null || filter.dateTo() != null;
  }

  private boolean hasAmountFilter(TransactionFilter filter) {
    return filter.minAmount() != null || filter.maxAmount() != null;
  }

  private boolean hasTimestampFilter(TransactionFilter filter) {
    return filter.createdAfter() != null
        || filter.createdBefore() != null
        || filter.updatedAfter() != null
        || filter.updatedBefore() != null;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
