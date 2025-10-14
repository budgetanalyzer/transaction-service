package com.bleurubin.budgetanalyzer.service.impl;

import com.bleurubin.budgetanalyzer.config.BudgetAnalyzerProperties;
import com.bleurubin.budgetanalyzer.domain.CsvData;
import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.service.CsvParser;
import com.bleurubin.budgetanalyzer.service.CsvService;
import com.bleurubin.budgetanalyzer.service.CsvTransactionMapper;
import com.bleurubin.budgetanalyzer.service.TransactionService;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
  }

  @Override
  @Transactional(rollbackFor = IOException.class)
  public List<Transaction> importCsvFile(String csvVersion, String accountId, MultipartFile file)
      throws IOException {
    log.trace(
        "Importing csv file csvVersion: {} for file: {}", csvVersion, file.getOriginalFilename());

    var csvData = csvParser.parseCsvFile(file);
    return createTransactions(csvVersion, accountId, csvData);
  }

  private List<Transaction> createTransactions(
      String csvVersion, String accountId, CsvData csvData) {
    var transactions =
        csvData.getRows().stream()
            .map(r -> transactionMapper.map(csvVersion, accountId, r))
            .toList();

    return transactionService.createTransactions(transactions);
  }
}
