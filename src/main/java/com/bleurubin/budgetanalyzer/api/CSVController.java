package com.bleurubin.budgetanalyzer.api;

import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.service.CSVService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping(path = "/csv")
public class CSVController {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private CSVService csvService;

    @PostMapping(path = "", produces = "application/json")
    public List<Transaction> uploadCSVFile(@RequestParam("file") MultipartFile file) {
        var bankCode = "capital-one";
        log.trace("Received uploadCSVFile request: bankCode: {}", bankCode);
        var rv = new ArrayList<Transaction>();

        if (file.isEmpty()) {
            log.warn("File is empty");
            return rv;
        }

        try {
            return csvService.importCSVFile(bankCode, file);
        } catch (Exception e) {
            log.warn("Error uploading file: {}", e.getMessage());
        }

        return rv;
    }
}
