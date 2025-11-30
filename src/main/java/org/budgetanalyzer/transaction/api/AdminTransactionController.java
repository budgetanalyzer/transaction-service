package org.budgetanalyzer.transaction.api;

import java.util.List;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.budgetanalyzer.core.logging.SafeLogger;
import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.api.response.TransactionResponse;
import org.budgetanalyzer.transaction.service.TransactionService;

/** Controller for admin transaction operations. */
@Tag(name = "Admin - Transactions", description = "Admin operations for transactions")
@RestController
@RequestMapping(path = "/v1/admin/transactions")
public class AdminTransactionController {

  private static final Logger log = LoggerFactory.getLogger(AdminTransactionController.class);

  private final TransactionService transactionService;

  public AdminTransactionController(TransactionService transactionService) {
    this.transactionService = transactionService;
  }

  @Operation(summary = "Search transactions", description = "Paginated search over transactions")
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
  @PostMapping(path = "/search", consumes = "application/json", produces = "application/json")
  public List<TransactionResponse> searchTransactions(
      @RequestBody @Valid TransactionFilter filter) {
    log.info("Received search request filter: {}", SafeLogger.toJson(filter));

    var transactions = transactionService.search(filter);
    return transactions.stream().map(TransactionResponse::from).toList();
  }
}
