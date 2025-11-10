package org.budgetanalyzer.transaction.api;

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
import org.budgetanalyzer.service.exception.InvalidRequestException;
import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.api.request.TransactionUpdateRequest;
import org.budgetanalyzer.transaction.api.response.TransactionResponse;
import org.budgetanalyzer.transaction.service.TransactionImportService;
import org.budgetanalyzer.transaction.service.TransactionService;

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
                    array =
                        @ArraySchema(
                            schema = @Schema(implementation = TransactionResponse.class)))),
        @ApiResponse(
            responseCode = "422",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = {
                      @ExampleObject(
                          name = "CSV Format Not Supported",
                          summary = "Invalid format parameter",
                          value =
                              """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "CSV format: fake-bank not supported",
                        "code": "CSV_FORMAT_NOT_SUPPORTED"
                      }
                      """),
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
                      """),
                      @ExampleObject(
                          name = "Transaction Date Too Old",
                          summary = "Date before year 2000",
                          value =
                              """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "Transaction date 1999-12-31 is prior to year 2000",
                        "code": "TRANSACTION_DATE_TOO_OLD"
                      }
                      """),
                      @ExampleObject(
                          name = "Transaction Too Far In Future",
                          summary = "Date more than 1 day in the future",
                          value =
                              """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "Transaction date '2030-01-01' at line 3 in file 'capital-one-2030.csv' is more than 1 day in the future. Future-dated transactions are not allowed to prevent data entry errors.",
                        "code": "TRANSACTION_DATE_TOO_FAR_IN_FUTURE"
                      }
                      """)
                    }))
      })
  @PostMapping(path = "/import", consumes = "multipart/form-data", produces = "application/json")
  public List<TransactionResponse> importTransactions(
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

    var transactions =
        transactionImportService.importCsvFiles(format, accountId.orElse(null), files);

    return transactions.stream().map(TransactionResponse::from).toList();
  }

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
    log.info("Received get transactions request");

    var transactions =
        transactionService.search(
            new TransactionFilter(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null));

    return transactions.stream().map(TransactionResponse::from).toList();
  }

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

    var transaction = transactionService.getTransaction(id);
    return TransactionResponse.from(transaction);
  }

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

    var updated =
        transactionService.updateTransaction(id, request.description(), request.accountId());
    return TransactionResponse.from(updated);
  }

  @Operation(summary = "Delete transaction", description = "Delete transaction by id")
  @ApiResponses(value = {@ApiResponse(responseCode = "204")})
  @DeleteMapping(path = "/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteTransaction(@PathVariable("id") Long id) {
    log.info("Received delete transaction request id: {}", id);

    transactionService.deleteTransaction(id);
  }
}
