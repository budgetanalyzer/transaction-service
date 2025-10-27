package com.bleurubin.budgetanalyzer.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.bleurubin.budgetanalyzer.config.BudgetAnalyzerProperties;
import com.bleurubin.budgetanalyzer.domain.CsvData;
import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.service.CsvParser;
import com.bleurubin.budgetanalyzer.service.CsvService;
import com.bleurubin.budgetanalyzer.service.TransactionService;
import com.bleurubin.budgetanalyzer.util.JsonUtils;

@Service
public class CsvServiceImpl implements CsvService {

  private final Logger log = LoggerFactory.getLogger(this.getClass());

  private final CsvParser csvParser;
  private final CsvTransactionMapper transactionMapper;
  private final TransactionService transactionService;

  public CsvServiceImpl(
      BudgetAnalyzerProperties appProperties,
      CsvParser csvParser,
      TransactionService transactionService) {
    this.csvParser = csvParser;
    this.transactionService = transactionService;
    this.transactionMapper = new CsvTransactionMapper(appProperties.csvConfigMap());

    log.info("Initializing application configuration: {}", JsonUtils.toJson(appProperties));
  }

  @Override
  @Transactional(rollbackFor = IOException.class)
  public List<Transaction> importCsvFiles(
      String format, String accountId, List<MultipartFile> files) throws IOException {
    var importedTransactions = new ArrayList<Transaction>();
    for (MultipartFile file : files) {
      if (file.isEmpty()) {
        log.warn("File {} is empty, skipping", file.getOriginalFilename());
        continue;
      }

      log.info("Importing csv file format: {} for file: {}", format, file.getOriginalFilename());

      var csvData = csvParser.parseCsvFile(file);
      importedTransactions.addAll(createTransactions(format, accountId, csvData));
    }

    log.info(
        "Successfully imported {} total transactions from {} files",
        importedTransactions.size(),
        files.size());

    return importedTransactions;
  }

  private List<Transaction> createTransactions(String format, String accountId, CsvData csvData) {
    var transactions =
        csvData.getRows().stream().map(r -> transactionMapper.map(format, accountId, r)).toList();

    return transactionService.createTransactions(transactions);
  }
}
