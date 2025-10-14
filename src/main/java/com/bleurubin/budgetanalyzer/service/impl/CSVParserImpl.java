package com.bleurubin.budgetanalyzer.service.impl;

import com.bleurubin.budgetanalyzer.domain.CSVData;
import com.bleurubin.budgetanalyzer.service.CSVParser;
import com.opencsv.CSVReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CSVParserImpl implements CSVParser {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Override
    public CSVData parseCSVFile(MultipartFile file) throws IOException {
        try (CSVReader csvReader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
            var allRows = csvReader.readAll();

            return allRows.isEmpty()
                    ? new CSVData()
                    : buildRowMap(allRows); // rows
        }
    }

    private CSVData buildRowMap(List<String[]> allRows) {
        var headers = allRows.getFirst();
        var rows = allRows.subList(1, allRows.size());
        List<Map<String, String>> mappedRows = new ArrayList<>();

        for (String[] row : rows) {
            var map = new HashMap<String, String>();
            for (int i = 0; i < row.length; i++) {
                var header = headers[i].trim();
                if (!header.isEmpty()) {
                    map.put(header, row[i].trim());
                }
            }

            mappedRows.add(map);
        }

        var rv = new CSVData();
        rv.setRows(mappedRows);

        return rv;
    }
}
