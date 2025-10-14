package com.bleurubin.budgetanalyzer.service.impl;

import com.bleurubin.budgetanalyzer.config.BudgetAnalyzerProperties;
import com.bleurubin.budgetanalyzer.domain.CSVData;
import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.service.CSVParser;
import com.bleurubin.budgetanalyzer.service.CSVService;
import com.bleurubin.budgetanalyzer.service.GenericTransactionMapper;
import com.bleurubin.budgetanalyzer.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
public class CSVServiceImpl implements CSVService {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private final CSVParser csvParser;
    private final GenericTransactionMapper transactionMapper;
    private final TransactionService transactionService;

    public CSVServiceImpl(BudgetAnalyzerProperties appProperties, CSVParser csvParser, TransactionService transactionService) {
        this.csvParser = csvParser;
        this.transactionService = transactionService;
        this.transactionMapper = new GenericTransactionMapper(appProperties.getBankConfigMap());
    }

    @Override
    @Transactional(rollbackFor = IOException.class)
    public List<Transaction> importCSVFile(String bankCode, MultipartFile file) throws IOException {
        var csvData = csvParser.parseCSVFile(file);
        return createTransactions(bankCode, csvData);
    }

    private List<Transaction> createTransactions(String bankCode, CSVData csvData) {
        var transactions = csvData.getRows().stream()
                .map(r -> transactionMapper.map(bankCode, r))
                .toList();

        return transactionService.createTransactions(transactions);
    }
}
