package com.bleurubin.budgetanalyzer.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.bleurubin.budgetanalyzer.config.BudgetAnalyzerProperties;
import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.service.BudgetAnalyzerError;
import com.bleurubin.budgetanalyzer.service.TransactionImportService;
import com.bleurubin.budgetanalyzer.service.TransactionService;
import com.bleurubin.core.csv.CsvData;
import com.bleurubin.core.csv.CsvParser;
import com.bleurubin.service.exception.BusinessException;

@Service
public class TransactionImportServiceImpl implements TransactionImportService {

  private static final Logger log = LoggerFactory.getLogger(TransactionImportServiceImpl.class);

  private final CsvParser csvParser;
  private final TransactionService transactionService;
  private final CsvTransactionMapper transactionMapper;

  public TransactionImportServiceImpl(
      BudgetAnalyzerProperties appProperties,
      CsvParser csvParser,
      TransactionService transactionService) {
    this.csvParser = csvParser;
    this.transactionService = transactionService;
    this.transactionMapper = new CsvTransactionMapper(appProperties.csvConfigMap());
  }

  @Override
  @Transactional
  public List<Transaction> importCsvFiles(
      String format, String accountId, List<MultipartFile> files) {
    try {
      var importedTransactions = new ArrayList<Transaction>();

      for (MultipartFile file : files) {
        if (file.isEmpty()) {
          log.warn("File {} is empty, skipping", file.getOriginalFilename());
          continue;
        }

        log.info("Importing csv file format: {} for file: {}", format, file.getOriginalFilename());

        var csvData = csvParser.parseCsvFile(file, format);
        var transactions = createTransactions(accountId, csvData);

        importedTransactions.addAll(transactions);
      }

      log.info(
          "Successfully imported {} total transactions from {} files",
          importedTransactions.size(),
          files.size());

      return importedTransactions;
    } catch (BusinessException businessException) {
      throw businessException;
    } catch (Exception e) {
      throw new BusinessException(
          "Failed to import CSV files: " + e.getMessage(),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name(),
          e);
    }
  }

  private List<Transaction> createTransactions(String accountId, CsvData csvData) {
    var transactions =
        csvData.rows().stream()
            .map(r -> transactionMapper.map(csvData.fileName(), csvData.format(), accountId, r))
            .toList();

    return transactionService.createTransactions(transactions);
  }
}
