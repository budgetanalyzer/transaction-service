package com.bleurubin.budgetanalyzer.service;

import com.bleurubin.budgetanalyzer.domain.CsvData;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

public interface CsvParser {
  CsvData parseCsvFile(MultipartFile file) throws IOException;
}
