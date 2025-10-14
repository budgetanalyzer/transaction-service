package com.bleurubin.budgetanalyzer.service;

import com.bleurubin.budgetanalyzer.domain.CSVData;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface CSVParser {
    CSVData parseCSVFile(MultipartFile file) throws IOException;
}
