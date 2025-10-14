package com.bleurubin.budgetanalyzer.service;

import com.bleurubin.budgetanalyzer.domain.Transaction;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface CSVService {
    List<Transaction> importCSVFile(String bankCode, MultipartFile file) throws IOException;
}
