package com.bleurubin.budgetanalyzer.api;

import com.bleurubin.budgetanalyzer.api.request.TransactionFilter;
import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.service.CsvService;
import com.bleurubin.budgetanalyzer.service.TransactionService;
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
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
  @PostMapping(path = "", consumes = "multipart/form-data", produces = "application/json")
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
      log.warn("File is empty");
      throw new IllegalArgumentException("File is empty");
    }

    try {
      return csvService.importCsvFile(format, accountId.orElse(null), file);
    } catch (IOException e) {
      log.warn("Error uploading file: {}", e.getMessage());
      throw e;
    }
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
    log.trace("Received search request filter: {}", filter);
    return transactionService.search(filter);
  }
}
