package com.bleurubin.budgetanalyzer.service;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

import com.bleurubin.core.domain.CsvData;

public interface CsvParser {

  CsvData parseCsvFile(MultipartFile file, String format) throws IOException;
}
