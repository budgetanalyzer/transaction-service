package com.bleurubin.budgetanalyzer.service.impl;

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

import com.opencsv.CSVReader;

import com.bleurubin.budgetanalyzer.service.CsvParser;
import com.bleurubin.core.domain.CsvData;
import com.bleurubin.core.domain.CsvRow;

@Service
public class CsvParserImpl implements CsvParser {

  private static final Logger log = LoggerFactory.getLogger(CsvParserImpl.class);

  @Override
  public CsvData parseCsvFile(MultipartFile file, String format) throws IOException {
    try (CSVReader csvReader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
      var allRows = csvReader.readAll();

      if (allRows.isEmpty()) {
        log.info("Ignoring empty csv file: {}", file.getOriginalFilename());
        return new CsvData(file.getOriginalFilename(), format, null);
      }

      return buildCsvData(file.getOriginalFilename(), format, allRows);
    }
  }

  private CsvData buildCsvData(String fileName, String format, List<String[]> allRows) {
    var headers = Arrays.stream(allRows.getFirst()).map(String::trim).toList();
    var rows = allRows.subList(1, allRows.size());
    var csvRows = new ArrayList<CsvRow>();

    for (int i = 0; i < rows.size(); i++) {
      var dataMap = buildDataMap(headers, rows.get(i));
      var csvRow = new CsvRow(i + 1, dataMap);

      csvRows.add(csvRow);
    }

    return new CsvData(fileName, format, csvRows);
  }

  private Map<String, String> buildDataMap(List<String> headers, String[] row) {
    var rowMap = new HashMap<String, String>();

    for (int i = 0; i < row.length; i++) {
      if (!headers.get(i).isEmpty()) {
        rowMap.put(headers.get(i), row[i].trim());
      }
    }

    return rowMap;
  }
}
