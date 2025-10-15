package com.bleurubin.budgetanalyzer.api;

import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.service.CsvService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Transaction Handler", description = "Endpoints for operations on transactions")
@RestController
@RequestMapping(path = "/transactions")
public class CsvController {

  private final Logger log = LoggerFactory.getLogger(this.getClass());

  private final CsvService csvService;

  public CsvController(CsvService csvService) {
    this.csvService = csvService;
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
      })
  @PostMapping(path = "", consumes = "multipart/form-data", produces = "application/json")
  public List<Transaction> uploadCsvFile(
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
}
