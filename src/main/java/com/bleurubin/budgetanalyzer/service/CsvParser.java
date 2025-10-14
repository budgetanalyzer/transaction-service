package com.bleurubin.budgetanalyzer.service;

import com.bleurubin.budgetanalyzer.domain.CsvData;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface CsvParser {
    CsvData parseCSVFile(MultipartFile file) throws IOException;
}
