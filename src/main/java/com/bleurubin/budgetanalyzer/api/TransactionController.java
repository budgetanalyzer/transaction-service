package com.bleurubin.budgetanalyzer.api;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

import com.bleurubin.budgetanalyzer.api.request.TransactionFilter;
import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.service.TransactionImportService;
import com.bleurubin.budgetanalyzer.service.TransactionService;
import com.bleurubin.core.logging.SafeLogger;
import com.bleurubin.service.api.ApiErrorResponse;
import com.bleurubin.service.exception.InvalidRequestException;

@Tag(name = "Transactions", description = "Import and manipulate transactions")
@RestController
@RequestMapping(path = "/v1/transactions")
public class TransactionController {

  private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

  private final TransactionImportService transactionImportService;
  private final TransactionService transactionService;

  public TransactionController(
      TransactionImportService csvService, TransactionService transactionService) {
    this.transactionImportService = csvService;
    this.transactionService = transactionService;
  }

  @Operation(
      summary = "Upload CSV file(s) containing transactions",
      description =
          "Imports transactions from one or more CSV files for a given account and format.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = Transaction.class)))),
        @ApiResponse(
            responseCode = "422",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = {
                      @ExampleObject(
                          name = "CSV Parsing Error",
                          summary = "Missing required column",
                          value =
                              """
                    {
                      "type": "APPLICATION_ERROR",
                      "message": "Missing value for required column 'Transaction Description' at line 1 in file 'bkk-bank-2025.csv'",
                      "code": "CSV_PARSING_ERROR"
                    }
                      """)
                    }))
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
      @Parameter(description = "CSV file(s) to upload", required = true)
          @NotNull
          @RequestParam("files")
          List<MultipartFile> files) {
    log.info(
        "Received importTransactions request format: {} accountId: {} fileCount: {} fileNames: {}",
        format,
        accountId.orElse(null),
        files.size(),
        files.stream().map(MultipartFile::getOriginalFilename).collect(Collectors.joining(", ")));

    if (files.isEmpty()) {
      log.warn("No files provided");
      throw new InvalidRequestException("No files provided");
    }

    return transactionImportService.importCsvFiles(format, accountId.orElse(null), files);
  }

  @Operation(summary = "Get transactions", description = "Get all transactions")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
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

  @Operation(summary = "Delete transaction", description = "Delete transaction by id")
  @ApiResponses(value = {@ApiResponse(responseCode = "204")})
  @DeleteMapping(path = "/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteTransaction(@PathVariable("id") Long id) {
    log.info("Received delete transaction request id: {}", id);
    transactionService.deleteTransaction(id);
  }

  @Operation(summary = "Search transactions", description = "Paginated search over transactions")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = Transaction.class)))),
      })
  @PostMapping(path = "/search", consumes = "application/json", produces = "application/json")
  public List<Transaction> searchTransactions(@RequestBody @Valid TransactionFilter filter) {
    log.info("Received search request filter: {}", SafeLogger.toJson(filter));
    return transactionService.search(filter);
  }
}
