package com.bleurubin.budgetanalyzer.service.impl;

import com.bleurubin.budgetanalyzer.domain.CsvData;
import com.bleurubin.budgetanalyzer.service.CsvParser;
import com.opencsv.CSVReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CsvParserImpl implements CsvParser {

  private final Logger log = LoggerFactory.getLogger(this.getClass());

  @Override
  public CsvData parseCsvFile(MultipartFile file) throws IOException {
    try (CSVReader csvReader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
      var allRows = csvReader.readAll();

      if (allRows.isEmpty()) {
        log.info("Ignoring empty csv file: {}", file.getOriginalFilename());
        return new CsvData();
      }

      return buildRowMap(allRows);
    }
  }

  private CsvData buildRowMap(List<String[]> allRows) {
    var headers = Arrays.stream(allRows.getFirst()).map(String::trim).toList();
    var rows = allRows.subList(1, allRows.size());
    var mappedRows = new ArrayList<Map<String, String>>();

    for (String[] row : rows) {
      var rowMap = new HashMap<String, String>();
      for (int i = 0; i < row.length; i++) {
        var header = headers.get(i);
        // skip but don't remove empty headers because we need the column and row indices to match
        // 1:1
        if (!header.isEmpty()) {
          rowMap.put(header, row[i].trim());
        }
      }

      mappedRows.add(rowMap);
    }

    var rv = new CsvData();
    rv.setRows(mappedRows);

    return rv;
  }
}
