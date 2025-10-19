package com.bleurubin.budgetanalyzer.api;

import com.bleurubin.budgetanalyzer.api.request.TransactionFilter;
import com.bleurubin.budgetanalyzer.api.response.ApiErrorResponse;
import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.service.CsvService;
import com.bleurubin.budgetanalyzer.service.TransactionService;
import com.bleurubin.budgetanalyzer.util.JsonUtils;
import com.bleurubin.budgetanalyzer.util.TransactionUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Transaction Handler", description = "Endpoints for operations on transactions")
@RestController
@RequestMapping(path = "/transactions")
public class TransactionController {

  private final Logger log = LoggerFactory.getLogger(this.getClass());

  private final CsvService csvService;
  private final TransactionService transactionService;

  public TransactionController(CsvService csvService, TransactionService transactionService) {
    this.csvService = csvService;
    this.transactionService = transactionService;
  }

  @Operation(
      summary = "Upload a CSV file containing transactions",
      description = "Imports transactions from a CSV file for a given account and format.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Transactions imported successfully",
            content =
                @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = Transaction.class)))),
        @ApiResponse(
            responseCode = "422",
            description =
                "Request was correctly formatted but there were business rules violated by the request so it couldn't be processed",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(path = "/import", consumes = "multipart/form-data", produces = "application/json")
  public List<Transaction> importTransactions(
      @Parameter(description = "CSV format type", example = "capital-one")
          @NotNull
          @RequestParam("format")
          String format,
      @Parameter(
              description = "Account ID to associate transactions with",
              example = "checking-12345")
          @RequestParam(name = "accountId", required = false)
          Optional<String> accountId,
      @Parameter(description = "CSV file to upload", required = true) @NotNull @RequestParam("file")
          MultipartFile file)
      throws IOException {
    log.trace(
        "Received uploadCSVFile request format: {} accountId: {} fileName: {}",
        format,
        accountId.orElse(null),
        file.getOriginalFilename());

    if (file.isEmpty()) {
      log.warn("File {} is empty", file.getOriginalFilename());
      throw new IllegalArgumentException("File is empty");
    }

    try {
      return csvService.importCsvFile(format, accountId.orElse(null), file);
    } catch (IOException e) {
      log.warn("Error uploading file: {}", e.getMessage());
      throw e;
    }
  }

  @Operation(summary = "Get transactions", description = "Get all transactions")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "List of transactions",
            content =
                @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = Transaction.class)))),
      })
  @GetMapping(path = "", produces = "application/json")
  public List<Transaction> getTransactions() {
    log.info("Received get transactions request");
    return transactionService.search(
        new TransactionFilter(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null));
  }

  @Operation(summary = "Get transaction", description = "Get transaction by id")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Transaction found",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = Transaction.class))),
      })
  @GetMapping(path = "/{id}", produces = "application/json")
  public Transaction getTransaction(@PathVariable("id") Long id) {
    log.info("Received get transaction request id: {}", id);
    return transactionService.getTransaction(id);
  }

  @Operation(summary = "Search transactions", description = "Paginated search over transactions")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Search completed without errors",
            content =
                @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = Transaction.class)))),
      })
  @PostMapping(path = "/search", consumes = "application/json", produces = "application/json")
  public List<Transaction> searchTransactions(@RequestBody @Valid TransactionFilter filter) {
    log.info("Received search request filter: {}", JsonUtils.toJson(filter));
    return transactionService.search(filter);
  }

  @Operation(
      summary = "Get pre-formatted summary of transactions",
      description = "Paginated search over transactions")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Summary was successfully generated",
            content =
                @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = Transaction.class)))),
      })
  @GetMapping(path = "/summary", produces = "application/json")
  public Map<String, BigDecimal> getTransactionSummary() {
    log.info("Received summary request");

    var transactions =
        transactionService.search(
            new TransactionFilter(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null));

    var rv = TransactionUtils.getMonthlyDebitTotals(transactions);
    var monthlyAverage = TransactionUtils.averageMonthlyTotal(rv);
    rv.put("Average", monthlyAverage);

    return rv;
  }
}
