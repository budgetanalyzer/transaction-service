package com.bleurubin.budgetanalyzer.service;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

import com.bleurubin.budgetanalyzer.domain.CsvData;

public interface CsvParser {

  CsvData parseCsvFile(MultipartFile file) throws IOException;
}
