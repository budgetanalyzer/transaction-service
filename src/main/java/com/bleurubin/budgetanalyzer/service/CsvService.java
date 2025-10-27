package com.bleurubin.budgetanalyzer.service;

import java.io.IOException;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.bleurubin.budgetanalyzer.domain.Transaction;

public interface CsvService {

  List<Transaction> importCsvFiles(String format, String accountId, List<MultipartFile> files)
      throws IOException;
}
