package com.bleurubin.budgetanalyzer.service;

import com.bleurubin.budgetanalyzer.domain.Transaction;
import java.io.IOException;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface CsvService {

  List<Transaction> importCsvFile(String format, String accountId, MultipartFile file)
      throws IOException;
}
