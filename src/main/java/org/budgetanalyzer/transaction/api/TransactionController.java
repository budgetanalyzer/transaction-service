package org.budgetanalyzer.transaction.api;

import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.budgetanalyzer.service.api.ApiErrorResponse;
import org.budgetanalyzer.service.api.PagedResponse;
import org.budgetanalyzer.service.exception.InvalidRequestException;
import org.budgetanalyzer.service.security.SecurityContextUtil;
import org.budgetanalyzer.transaction.api.request.BatchImportRequest;
import org.budgetanalyzer.transaction.api.request.BatchImportTransactionRequest;
import org.budgetanalyzer.transaction.api.request.BulkDeleteRequest;
import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.api.request.TransactionUpdateRequest;
import org.budgetanalyzer.transaction.api.response.BatchImportResponse;
import org.budgetanalyzer.transaction.api.response.BulkDeleteResponse;
import org.budgetanalyzer.transaction.api.response.PreviewResponse;
import org.budgetanalyzer.transaction.api.response.TransactionResponse;
import org.budgetanalyzer.transaction.service.TransactionImportService;
import org.budgetanalyzer.transaction.service.TransactionService;

@Tag(name = "Transactions", description = "Import and manipulate transactions")
@RestController
@RequestMapping(path = "/v1/transactions")
public class TransactionController {

  private static final Logger log = LoggerFactory.getLogger(TransactionController.class);
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

  private final TransactionImportService transactionImportService;
  private final TransactionService transactionService;

  public TransactionController(
      TransactionImportService transactionImportService, TransactionService transactionService) {
    this.transactionImportService = transactionImportService;
    this.transactionService = transactionService;
  }

  @PreAuthorize("hasAuthority('transactions:read')")
  @Operation(
      summary = "Preview transactions from a file before import",
      description =
          "Parses a CSV or PDF file and returns the extracted transactions for review and editing "
              + "before batch import. No data is persisted. The format parameter is required and "
              + "determines which parser to use. The response includes any parsing warnings.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PreviewResponse.class))),
        @ApiResponse(
            responseCode = "422",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = {
                      @ExampleObject(
                          name = "Format Not Supported",
                          summary = "Invalid format parameter",
                          value =
                              """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "Format not supported: fake-bank",
                        "code": "FORMAT_NOT_SUPPORTED"
                      }
                      """),
                      @ExampleObject(
                          name = "Parsing Error",
                          summary = "Missing required column",
                          value =
                              """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "Missing value for required column 'Description' at line 1",
                        "code": "CSV_PARSING_ERROR"
                      }
                      """)
                    }))
      })
  @PostMapping(path = "/preview", consumes = "multipart/form-data", produces = "application/json")
  public PreviewResponse previewTransactions(
      @Parameter(
              description =
                  "Format key (e.g., 'capital-one-yearly' for PDF, 'capital-one' for CSV). "
                      + "Use GET /v1/statement-formats to list available formats.",
              required = true,
              example = "capital-one-yearly")
          @NotNull
          @RequestParam(name = "format")
          String format,
      @Parameter(
              description = "Account ID to pre-fill for all transactions",
              example = "checking-12345")
          @RequestParam(name = "accountId", required = false)
          Optional<String> accountId,
      @Parameter(description = "CSV or PDF file to preview", required = true)
          @NotNull
          @RequestParam("file")
          MultipartFile file) {
    log.info(
        "Received preview request format: {} accountId: {} fileName: {}",
        format,
        accountId.orElse(null),
        file.getOriginalFilename());

    return PreviewResponse.from(
        transactionImportService.previewFile(format, accountId.orElse(null), file));
  }

  @PreAuthorize("hasAuthority('transactions:write')")
  @Operation(
      summary = "Import a batch of transactions",
      description =
          "Imports transactions from a batch request (typically from the preview endpoint after "
              + "user edits). Validates all transactions upfront and rejects the entire batch if "
              + "any fail. Duplicates (matching date + amount + description) are skipped.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = BatchImportResponse.class))),
        @ApiResponse(
            responseCode = "400",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = {
                      @ExampleObject(
                          name = "Validation Failed",
                          summary = "One or more transactions failed validation",
                          value =
                              """
                      {
                        "type": "VALIDATION_ERROR",
                        "message": "Validation failed for 2 field(s)",
                        "fieldErrors": [
                          { "field": "transactions[44].amount", "message": "must not be null" },
                          { "field": "transactions[89].date", "message": "must not be null" }
                        ]
                      }
                      """)
                    }))
      })
  @PostMapping(path = "/batch", consumes = "application/json", produces = "application/json")
  public BatchImportResponse batchImportTransactions(
      @Valid @RequestBody BatchImportRequest request) {
    log.info("Received batch import request with {} transactions", request.transactions().size());

    var userId = getCurrentUserId();
    var serviceDtos =
        request.transactions().stream().map(BatchImportTransactionRequest::toServiceDto).toList();
    var result = transactionService.batchImport(serviceDtos, userId);

    return new BatchImportResponse(
        result.createdTransactions().size(),
        result.duplicatesSkipped(),
        result.createdTransactions().stream().map(TransactionResponse::from).toList());
  }

  @PreAuthorize("hasAuthority('transactions:read')")
  @Operation(summary = "Get transactions", description = "Get all transactions")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    array =
                        @ArraySchema(
                            schema = @Schema(implementation = TransactionResponse.class)))),
      })
  @GetMapping(path = "", produces = "application/json")
  public List<TransactionResponse> getTransactions() {
    var userId = getCurrentUserId();
    log.info("Received get transactions request - User ID: {}", userId);

    var transactions = transactionService.getTransactions(userId);

    return transactions.stream().map(TransactionResponse::from).toList();
  }

  @PreAuthorize("hasAuthority('transactions:read')")
  @Operation(
      summary = "Count transactions",
      description =
          "Returns the count of active transactions owned by the requesting user and matching the "
              + "given filter criteria.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = Long.class))),
      })
  @GetMapping(path = "/count", produces = "application/json")
  public long countTransactions(@Valid TransactionFilter filter) {
    var userId = getCurrentUserId();
    return transactionService.countNotDeletedForUser(filter, userId);
  }

  @PreAuthorize("hasAuthority('transactions:read:any')")
  @Operation(summary = "Search transactions across users")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Transactions retrieved successfully",
            useReturnTypeSchema = true),
        @ApiResponse(
            responseCode = "400",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples =
                        @ExampleObject(
                            name = "Invalid Sort Field",
                            summary = "Unsupported sort field requested",
                            value =
                                """
                        {
                          "type": "INVALID_REQUEST",
                          "message": "Unsupported sort field: invalid. Allowed sort fields: id, ownerId, accountId, bankName, date, currencyIsoCode, amount, type, description, createdAt, updatedAt"
                        }
                        """)))
      })
  @GetMapping(path = "/search", produces = "application/json")
  public PagedResponse<TransactionResponse> searchTransactions(
      @ParameterObject @Valid TransactionFilter filter,
      @ParameterObject
          @PageableDefault(
              size = 50,
              sort = {"date", "id"},
              direction = Sort.Direction.DESC)
          Pageable pageable) {
    validateSortFields(pageable);
    log.info(
        "Cross-user transaction search request - page: {} size: {} sort: {} "
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

    return PagedResponse.from(page, TransactionResponse::from);
  }

  @PreAuthorize("hasAuthority('transactions:read:any')")
  @Operation(summary = "Count transactions across users")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = Long.class))),
      })
  @GetMapping(path = "/search/count", produces = "application/json")
  public long countTransactionsAcrossUsers(@ParameterObject @Valid TransactionFilter filter) {
    log.info(
        "Cross-user transaction count request - hasIdentityFilters: {} hasTextFilters: {} "
            + "hasDateFilter: {} hasAmountFilter: {} hasTimestampFilter: {}",
        hasIdentityFilters(filter),
        hasTextFilters(filter),
        hasDateFilter(filter),
        hasAmountFilter(filter),
        hasTimestampFilter(filter));
    return transactionService.countNotDeleted(filter);
  }

  @PreAuthorize("hasAuthority('transactions:read')")
  @Operation(summary = "Get transaction", description = "Get transaction by id")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TransactionResponse.class))),
      })
  @GetMapping(path = "/{id}", produces = "application/json")
  public TransactionResponse getTransaction(@PathVariable("id") Long id) {
    log.info("Received get transaction request id: {}", id);

    var userId = getCurrentUserId();
    var canActOnAny = SecurityContextUtil.hasAuthority("transactions:read:any");
    var transaction = transactionService.getTransaction(id, userId, canActOnAny);
    return TransactionResponse.from(transaction);
  }

  @PreAuthorize("hasAuthority('transactions:write')")
  @Operation(
      summary = "Update transaction",
      description =
          "Updates mutable fields (description and accountId) of a transaction. "
              + "All other fields are immutable to preserve financial data integrity.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TransactionResponse.class))),
        @ApiResponse(
            responseCode = "404",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples =
                        @ExampleObject(
                            name = "Transaction Not Found",
                            summary = "Transaction does not exist or is deleted",
                            value =
                                """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "Transaction not found with id: 999"
                      }
                      """)))
      })
  @PatchMapping(path = "/{id}", consumes = "application/json", produces = "application/json")
  public TransactionResponse updateTransaction(
      @PathVariable("id") Long id, @Valid @RequestBody TransactionUpdateRequest request) {
    log.info(
        "Received update transaction request id: {} description: {} accountId: {}",
        id,
        request.description(),
        request.accountId());

    var userId = getCurrentUserId();
    var canActOnAny = SecurityContextUtil.hasAuthority("transactions:write:any");
    var updated =
        transactionService.updateTransaction(
            id, userId, canActOnAny, request.description(), request.accountId());
    return TransactionResponse.from(updated);
  }

  @PreAuthorize("hasAuthority('transactions:delete')")
  @Operation(summary = "Delete transaction", description = "Delete transaction by id")
  @ApiResponses(value = {@ApiResponse(responseCode = "204")})
  @DeleteMapping(path = "/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteTransaction(@PathVariable("id") Long id) {
    log.info("Received delete transaction request id: {}", id);

    var userId = getCurrentUserId();
    var canActOnAny = SecurityContextUtil.hasAuthority("transactions:delete:any");
    transactionService.deleteTransaction(id, userId, canActOnAny);
  }

  @PreAuthorize("hasAuthority('transactions:delete')")
  @Operation(
      summary = "Bulk delete transactions",
      description =
          "Soft-deletes multiple transactions in a single operation. Returns the count of "
              + "successfully deleted transactions and any IDs that were not found or already "
              + "deleted.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = BulkDeleteResponse.class),
                    examples =
                        @ExampleObject(
                            name = "Successful bulk delete",
                            summary = "All transactions deleted",
                            value =
                                """
                      {
                        "deletedCount": 3,
                        "notFoundIds": []
                      }
                      """)),
            description = "Bulk delete operation completed"),
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = BulkDeleteResponse.class),
                    examples =
                        @ExampleObject(
                            name = "Partial success",
                            summary = "Some IDs not found",
                            value =
                                """
                      {
                        "deletedCount": 2,
                        "notFoundIds": [999, 1000]
                      }
                      """)),
            description = "Some transactions not found"),
        @ApiResponse(
            responseCode = "400",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)),
            description = "Invalid request (empty ID list)")
      })
  @PostMapping(path = "/bulk-delete", consumes = "application/json", produces = "application/json")
  public BulkDeleteResponse bulkDeleteTransactions(@Valid @RequestBody BulkDeleteRequest request) {
    log.info("Received bulk delete request for {} transaction IDs", request.ids().size());

    var userId = getCurrentUserId();
    var canActOnAny = SecurityContextUtil.hasAuthority("transactions:delete:any");

    var result = transactionService.bulkDeleteTransactions(request.ids(), userId, canActOnAny);

    log.info(
        "Bulk delete completed: {} deleted, {} not found",
        result.deletedCount(),
        result.notFoundIds().size());

    return new BulkDeleteResponse(result.deletedCount(), result.notFoundIds());
  }

  private String getCurrentUserId() {
    return SecurityContextUtil.getCurrentUserId()
        .orElseThrow(() -> new IllegalStateException("User ID not found in security context"));
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
