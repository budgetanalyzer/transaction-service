package org.budgetanalyzer.transaction.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import org.budgetanalyzer.core.csv.CsvData;
import org.budgetanalyzer.core.csv.CsvParser;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.config.TransactionServiceProperties;
import org.budgetanalyzer.transaction.domain.Transaction;

/** Service for importing transactions from CSV files. */
@Service
public class TransactionImportService {

  private static final Logger log = LoggerFactory.getLogger(TransactionImportService.class);

  private final CsvParser csvParser;
  private final TransactionService transactionService;
  private final CsvTransactionMapper transactionMapper;

  /**
   * Constructs a new TransactionImportService.
   *
   * @param appProperties the application properties containing CSV configuration
   * @param csvParser the CSV parser utility
   * @param transactionService the transaction service for persisting transactions
   */
  public TransactionImportService(
      TransactionServiceProperties appProperties,
      CsvParser csvParser,
      TransactionService transactionService) {
    this.csvParser = csvParser;
    this.transactionService = transactionService;
    this.transactionMapper = new CsvTransactionMapper(appProperties.csvConfigMap());
  }

  /**
   * Imports transactions from CSV files for a specified bank format.
   *
   * @param format the CSV format key matching configuration in application.yml
   * @param accountId optional account identifier to associate with imported transactions
   * @param files the list of CSV files to import
   * @return the list of imported transactions
   */
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
