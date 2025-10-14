package com.bleurubin.budgetanalyzer.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CSVData {
    
    private List<Map<String, String>> rows = new ArrayList<>();

    public CSVData() {
    }

    public List<Map<String, String>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, String>> rows) {
        this.rows = rows;
    }
}
