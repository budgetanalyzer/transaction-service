package com.bleurubin.budgetanalyzer.api;

import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.service.CsvService;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(path = "/csv")
public class CsvController {

  private final Logger log = LoggerFactory.getLogger(this.getClass());

  private final CsvService csvService;

  public CsvController(CsvService csvService) {
    this.csvService = csvService;
  }

  @PostMapping(path = "", produces = "application/json")
  public List<Transaction> uploadCsvFile(
      @NotNull @RequestParam("csvVersion") String csvVersion,
      @RequestParam("accountId") String accountId,
      @RequestParam("file") MultipartFile file)
      throws IOException {
    log.trace(
        "Received uploadCSVFile request csvVersion: {} accountId: {} fileName: {}",
        csvVersion,
        accountId,
        file.getOriginalFilename());

    if (file.isEmpty()) {
      // nothing imported, could throw error, just return empty list for simplicity
      log.warn("File is empty");
      return new ArrayList<>();
    }

    try {
      return csvService.importCsvFile(csvVersion, accountId, file);
    } catch (IOException e) {
      log.warn("Error uploading file: {}", e.getMessage());
      throw e;
    }
  }
}
