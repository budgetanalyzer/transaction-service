package com.bleurubin.budgetanalyzer.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.bleurubin.budgetanalyzer.domain.Transaction;

/** Service for importing transactions from CSV files. */
public interface TransactionImportService {

  /**
   * Imports transactions from CSV files for a specified bank format.
   *
   * @param format the CSV format key matching configuration in application.yml
   * @param accountId optional account identifier to associate with imported transactions
   * @param files the list of CSV files to import
   * @return the list of imported transactions
   */
  List<Transaction> importCsvFiles(String format, String accountId, List<MultipartFile> files);
}
